export type LoginErrorCode =
  | 'INVALID_CREDENTIALS'
  | 'TOTP_REQUIRED'
  | 'TOTP_INVALID'
  | 'LOGIN_RATE_LIMITED'
  | 'TOTP_RATE_LIMITED'

export interface LoginFailureState {
  totpRequired: boolean
  clearTotp: boolean
  clearPassword: boolean
  focusTotp: boolean
  message: string
}

const messages: Record<LoginErrorCode, string> = {
  INVALID_CREDENTIALS: '账号或密码不正确，请重新输入',
  TOTP_REQUIRED: '请输入验证码或恢复码继续登录',
  TOTP_INVALID: '验证码或恢复码不正确，请重试',
  LOGIN_RATE_LIMITED: '登录尝试过多，请稍后重试',
  TOTP_RATE_LIMITED: '验证码错误次数过多，请稍后重试'
}

export function resolveLoginFailure(
  errorCode: string | undefined,
  fallbackMessage: string,
  currentTotpRequired: boolean
): LoginFailureState {
  switch (errorCode as LoginErrorCode | undefined) {
    case 'TOTP_REQUIRED':
      return { totpRequired: true, clearTotp: true, clearPassword: false, focusTotp: true, message: messages.TOTP_REQUIRED }
    case 'TOTP_INVALID':
      return { totpRequired: true, clearTotp: true, clearPassword: false, focusTotp: true, message: messages.TOTP_INVALID }
    case 'TOTP_RATE_LIMITED':
      return { totpRequired: true, clearTotp: true, clearPassword: false, focusTotp: false, message: messages.TOTP_RATE_LIMITED }
    case 'LOGIN_RATE_LIMITED':
      return { totpRequired: false, clearTotp: true, clearPassword: false, focusTotp: false, message: messages.LOGIN_RATE_LIMITED }
    case 'INVALID_CREDENTIALS':
      return { totpRequired: false, clearTotp: true, clearPassword: true, focusTotp: false, message: messages.INVALID_CREDENTIALS }
    default:
      return {
        totpRequired: currentTotpRequired,
        clearTotp: false,
        clearPassword: false,
        focusTotp: false,
        message: fallbackMessage || '登录服务暂不可用，请稍后重试'
      }
  }
}
