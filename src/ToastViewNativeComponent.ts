import { codegenNativeComponent } from 'react-native';
import type { CodegenTypes, ColorValue, ViewProps } from 'react-native';

export interface NativeProps extends ViewProps {
  visible: boolean;
  /** SF Symbol name. See https://developer.apple.com/sf-symbols/ */
  icon?: string;
  /** URI for a custom icon image (resolved from ImageSourcePropType). */
  iconUri?: string;
  title?: string;
  message?: string;
  duration?: CodegenTypes.Int32;
  autoDismiss?: boolean;
  enableSwipeDismiss?: boolean;
  useDynamicIsland?: boolean;
  accentColor?: ColorValue;
  strokeColor?: ColorValue;
  disableBackdropSampling?: boolean;
  actionLabel?: string;
  onToastDismiss?: CodegenTypes.BubblingEventHandler<null>;
  onToastShow?: CodegenTypes.BubblingEventHandler<null>;
  onToastPress?: CodegenTypes.BubblingEventHandler<null>;
  onToastActionPress?: CodegenTypes.BubblingEventHandler<null>;
}

export default codegenNativeComponent<NativeProps>('ToastView');
