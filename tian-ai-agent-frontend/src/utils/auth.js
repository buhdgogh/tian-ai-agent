// Redirect only within the app after a successful login.
export const loginDestination = value => typeof value === 'string'
  && value.startsWith('/') && !value.startsWith('//') && !value.includes('\\')
  && !/^\/login(?:[/?#]|$)/.test(value) ? value : '/'
