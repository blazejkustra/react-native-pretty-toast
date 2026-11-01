package com.toast

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.toast.anim.CutoutMorphAnimator
import com.toast.anim.SlideAnimator
import com.toast.anim.ToastAnimator
import com.toast.backdrop.BackdropSampler
import com.toast.backdrop.OutlineController
import com.toast.cutout.CutoutDetector
import com.toast.gesture.ToastGestureHandler
import com.toast.ui.IconMapper
import com.toast.ui.ToastViewFactory
import com.toast.util.Density
import com.toast.util.StatusBarController
import com.toast.util.ToastConstants.DISMISS_CALLBACK_BUFFER_MS

/**
 * Thin orchestrator over the toast subsystems:
 *   - [CutoutDetector] reads device geometry into a snapshot
 *   - [ToastViewFactory] builds the view hierarchy from that snapshot
 *   - A [ToastAnimator] (cutout-morph or slide) drives show/dismiss/drag
 *   - [ToastGestureHandler] wires touches into the animator
 *
 * Owns only lifecycle/state: show flags, auto-dismiss timer, and the
 * useDynamicIsland-changed recreate rule.
 */
class ToastOverlay(activity: Activity) {

    // Hold the hosting Activity weakly so that if it's destroyed (rotation,
    // finish, process trim) while the overlay still has state lying around,
    // we don't keep it alive — we just bail out of any operation that needs it.
    private val activityRef = WeakReference(activity)
    private val activity: Activity?
        get() = activityRef.get()

    private var views: ToastViewFactory.Built? = null
    private var animator: ToastAnimator? = null
    private var cutoutAnimator: CutoutMorphAnimator? = null
    private var isCutoutMorph = false
    private var backdropSampler: BackdropSampler? = null
    private var outline: OutlineController? = null

    private val handler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null
    // Pending status-bar restore. Scheduled after the collapse animation so
    // the bar doesn't flicker between queued toasts. Cancelled by the next
    // show() if one arrives before it fires.
    private var statusBarRestoreRunnable: Runnable? = null
    private var isShowing = false
    private var isDismissing = false
    private var useDynamicIslandProp = true

    var onDismiss: (() -> Unit)? = null
    var onPress: (() -> Unit)? = null
    var onActionPress: (() -> Unit)? = null

    private val imageLoader: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ToastIconLoader").apply { isDaemon = true }
    }
    // Bitmaps keyed by URI so a repeat show of the same custom icon paints
    // synchronously — no flash of the default drawable, no re-fetch.
    private val iconCache = LruCache<String, Bitmap>(8)
    // Tracks the most recently requested URI. Async loads that come back
    // after a newer request fires are dropped (late response, stale data).
    private var currentImageUri: String = ""

    fun show(
        icon: String,
        iconUri: String,
        title: String,
        message: String,
        duration: Int,
        autoDismiss: Boolean,
        enableSwipeDismiss: Boolean,
        useDynamicIsland: Boolean,
        accentColor: Int?,
        strokeColor: Int?,
        disableBackdropSampling: Boolean,
        actionLabel: String,
    ) {
        if (useDynamicIsland != this.useDynamicIslandProp) {
            this.useDynamicIslandProp = useDynamicIsland
            destroy()
        }
        this.useDynamicIslandProp = useDynamicIsland

        if (isDismissing) {
            handler.postDelayed({
                show(icon, iconUri, title, message, duration, autoDismiss, enableSwipeDismiss, useDynamicIsland, accentColor, strokeColor, disableBackdropSampling, actionLabel)
            }, 50)
            return
        }

        cancelAutoDismiss()
        isDismissing = false

        val built = ensureOverlay() ?: return
        val currentAnimator = animator ?: return

        updateContent(built, icon, iconUri, title, message, accentColor)
        bindAction(built, actionLabel, accentColor)
        outline?.setOverride(strokeColor)
        installGestures(built, currentAnimator, enableSwipeDismiss)

        if (!isShowing) {
            isShowing = true
            built.container.visibility = View.VISIBLE

            built.pill.animate().cancel()
            built.content.animate().cancel()
            built.pill.translationY = 0f
            built.pill.scaleX = 1f
            built.pill.scaleY = 1f
            built.pill.alpha = 1f
            built.content.alpha = 1f

            currentAnimator.show()
            if (!disableBackdropSampling) {
                startBackdropSampling()
            } else {
                stopBackdropSampling()
            }
        }

        if (autoDismiss && duration > 0) {
            dismissRunnable = Runnable { dismiss() }
            handler.postDelayed(dismissRunnable!!, duration.toLong())
        }
    }

    /**
     * Mutates the currently presented toast in place. Updates content on the
     * live view hierarchy and restarts the auto-dismiss timer — does NOT
     * re-run the expand animation.
     */
    fun update(
        icon: String,
        iconUri: String,
        title: String,
        message: String,
        duration: Int,
        autoDismiss: Boolean,
        accentColor: Int?,
        strokeColor: Int?,
        disableBackdropSampling: Boolean,
        actionLabel: String,
    ) {
        val built = views ?: return
        if (!isShowing || isDismissing) return

        updateContent(built, icon, iconUri, title, message, accentColor)
        bindAction(built, actionLabel, accentColor)
        outline?.setOverride(strokeColor)

        if (disableBackdropSampling) {
            stopBackdropSampling()
        } else if (backdropSampler == null) {
            startBackdropSampling()
        }

        cancelAutoDismiss()
        if (autoDismiss && duration > 0) {
            dismissRunnable = Runnable { dismiss() }
            handler.postDelayed(dismissRunnable!!, duration.toLong())
        }
    }

    fun dismiss() {
        if (!isShowing || isDismissing) return
        isDismissing = true
        cancelAutoDismiss()

        // Stop sampling + freeze any in-flight stroke crossfade before we
        // hand off to the animator. Runs on every dismiss path — including
        // the `animator == null` early return below — so we never leave a
        // tick runnable or a ValueAnimator running against a view that's
        // about to go away.
        stopBackdropSampling()
        outline?.cancel()

        val currentAnimator = animator ?: run {
            isShowing = false
            isDismissing = false
            onDismiss?.invoke()
            return
        }

        currentAnimator.dismiss {
            isShowing = false
            isDismissing = false
            views?.container?.visibility = View.GONE
            if (isCutoutMorph) {
                scheduleStatusBarRestore()
                // Match iOS's 50ms buffer between morph end and onDismiss callback.
                handler.postDelayed({ onDismiss?.invoke() }, DISMISS_CALLBACK_BUFFER_MS)
            } else {
                onDismiss?.invoke()
            }
        }
    }

    fun destroy() {
        cancelAutoDismiss()
        cancelStatusBarRestore()
        stopBackdropSampling()
        outline?.cancel()
        outline = null
        handler.removeCallbacksAndMessages(null)
        cutoutAnimator?.cancelPendingCallbacks()

        val decorView = activity?.window?.decorView as? ViewGroup
        views?.container?.let { container ->
            decorView?.post {
                if (container.parent === decorView) decorView.removeView(container)
            }
        }

        imageLoader.shutdownNow()
        iconCache.evictAll()

        views = null
        animator = null
        cutoutAnimator = null
        isShowing = false
        isDismissing = false
    }

    private fun ensureOverlay(): ToastViewFactory.Built? {
        views?.let { return it }

        val activity = this.activity ?: return null
        val decorView = activity.window?.decorView as? ViewGroup ?: return null
        val density = Density.from(activity.resources)

        val info = CutoutDetector.detect(activity, decorView, useDynamicIsland = useDynamicIslandProp)
        isCutoutMorph = info.hasCutout

        val factory = ToastViewFactory(activity, density)
        val built = factory.build(info)
        decorView.addView(built.container)

        animator = if (info.hasCutout) {
            CutoutMorphAnimator(
                pill = built.pill,
                content = built.content,
                info = info,
                expandedCornerRadius = built.expandedCornerRadius,
                density = density,
                onBeforeShow = {
                    cancelStatusBarRestore()
                    this.activity?.let { StatusBarController.hide(it) }
                },
            ).also { cutoutAnimator = it }
        } else {
            SlideAnimator(built.pill, density)
        }

        outline = OutlineController(
            pillBackground = built.pillBackground,
            strokeWidthPx = built.strokeWidthPx,
        )

        views = built
        return built
    }

    // MARK: - Backdrop sampling
    //
    // Mirrors iOS's PassThroughWindow sampler: while the toast is on-screen,
    // average the luminance of the top strip of the app's content view and
    // flip the outline between the toast's accent colour and a faint neutral
    // white. `OutlineController` handles the 300 ms ARGB crossfade between
    // the two stroke colours so the change is a soft transition, not a pop.

    private fun startBackdropSampling() {
        val activity = this.activity ?: return
        val density = Density.from(activity.resources)
        stopBackdropSampling()
        val sampler = BackdropSampler(
            activity = activity,
            density = density,
            onTintChanged = { tint -> outline?.setTint(tint, animated = true) },
        )
        backdropSampler = sampler
        sampler.start()
        // Seed the stroke with the sampler's first reading (which it computed
        // synchronously in start()) so we don't flash the default grey tint.
        outline?.setTint(sampler.tint, animated = false)
    }

    private fun stopBackdropSampling() {
        backdropSampler?.stop()
        backdropSampler = null
    }

    private fun updateContent(
        built: ToastViewFactory.Built,
        icon: String,
        iconUri: String,
        title: String,
        message: String,
        accentOverride: Int?,
    ) {
        val (drawableRes, defaultTint) = IconMapper.map(icon)
        val tint = accentOverride ?: defaultTint
        // Accent always flows to the outline stroke, regardless of which
        // icon path we take.
        outline?.setAccent(tint)

        currentImageUri = iconUri
        if (iconUri.isNotEmpty()) {
            val cached = iconCache[iconUri]
            if (cached != null) {
                // Repeat show — apply synchronously so there's no flash of the
                // default drawable while we "re-load" something we already have.
                built.icon.setImageBitmap(cached)
                built.icon.colorFilter = null
            } else {
                // Clear the view so the previous toast's icon (or any default)
                // doesn't hang around while we fetch. The ImageView keeps its
                // frame, so layout doesn't jump.
                built.icon.setImageDrawable(null)
                built.icon.colorFilter = null
                loadIcon(built, iconUri)
            }
        } else {
            built.icon.setImageResource(drawableRes)
            built.icon.setColorFilter(tint)
        }

        built.title.text = title
        if (message.isNotEmpty()) {
            built.message.text = message
            built.message.visibility = View.VISIBLE
        } else {
            built.message.visibility = View.GONE
        }
    }

    private fun bindAction(
        built: ToastViewFactory.Built,
        label: String,
        accentOverride: Int?,
    ) {
        if (label.isEmpty()) {
            built.actionButton.visibility = View.GONE
            built.actionButton.setOnClickListener(null)
            return
        }
        built.actionButton.visibility = View.VISIBLE
        built.actionButton.text = label
        accentOverride?.let { built.actionButton.setTextColor(it) }
        built.actionButton.setOnClickListener { onActionPress?.invoke() }
    }

    /**
     * Accepts http(s)://, file://, and absolute filesystem paths. Bundled
     * RN asset URIs in prod (resource names) are not supported here — users
     * passing `require('./icon.png')` will get the dev-mode http URL in dev
     * and will need a file URI in prod. Decoded bitmaps are cached in
     * [iconCache] so repeat shows apply synchronously.
     */
    private fun loadIcon(built: ToastViewFactory.Built, uri: String) {
        val targetW = built.icon.layoutParams.width.takeIf { it > 0 } ?: DEFAULT_ICON_TARGET_PX
        val targetH = built.icon.layoutParams.height.takeIf { it > 0 } ?: DEFAULT_ICON_TARGET_PX
        imageLoader.execute {
            val bitmap = try {
                when {
                    uri.startsWith("http://") || uri.startsWith("https://") -> {
                        val bytes = URL(uri).openStream().use { it.readBytes() }
                        decodeSampled(bytes, targetW, targetH)
                    }
                    uri.startsWith("file://") -> decodeSampled(uri.removePrefix("file://"), targetW, targetH)
                    uri.startsWith("/") -> decodeSampled(uri, targetW, targetH)
                    else -> null
                }
            } catch (_: Throwable) {
                null
            } ?: return@execute

            iconCache.put(uri, bitmap)
            handler.post {
                // Only apply if this URI is still the one we want to render.
                // A later show() with a different URI cancels this load.
                if (currentImageUri == uri) {
                    built.icon.setImageBitmap(bitmap)
                    built.icon.colorFilter = null
                }
            }
        }
    }

    private fun decodeSampled(bytes: ByteArray, targetW: Int, targetH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = calcInSampleSize(bounds.outWidth, bounds.outHeight, targetW, targetH)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun decodeSampled(path: String, targetW: Int, targetH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = calcInSampleSize(bounds.outWidth, bounds.outHeight, targetW, targetH)
        }
        return BitmapFactory.decodeFile(path, opts)
    }

    private fun calcInSampleSize(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Int {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return 1
        var sample = 1
        while (srcW / (sample * 2) >= dstW && srcH / (sample * 2) >= dstH) {
            sample *= 2
        }
        return sample
    }

    private fun installGestures(
        built: ToastViewFactory.Built,
        animator: ToastAnimator,
        enableSwipeDismiss: Boolean,
    ) {
        val resources = activity?.resources ?: return
        val density = Density.from(resources)
        ToastGestureHandler(
            animator = animator,
            density = density,
            enableSwipeDismiss = enableSwipeDismiss,
            onDismissRequested = { dismiss() },
            onPress = { onPress?.invoke() },
        ).install(built.pill)
    }

    private fun cancelAutoDismiss() {
        dismissRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = null
    }

    // The animator completion already fires after the collapse animation ends;
    // the extra delay here is a grace window so a queued toast's show() can
    // cancel the restore before the status bar visibly flashes.
    private fun scheduleStatusBarRestore() {
        cancelStatusBarRestore()
        val runnable = Runnable {
            activity?.let { StatusBarController.show(it) }
        }
        statusBarRestoreRunnable = runnable
        handler.postDelayed(runnable, STATUS_BAR_RESTORE_GRACE_MS)
    }

    private fun cancelStatusBarRestore() {
        statusBarRestoreRunnable?.let { handler.removeCallbacks(it) }
        statusBarRestoreRunnable = null
    }

    companion object {
        private const val STATUS_BAR_RESTORE_GRACE_MS = 250L
        // Fallback decode target if the ImageView layout params are WRAP/MATCH.
        private const val DEFAULT_ICON_TARGET_PX = 128
    }
}
