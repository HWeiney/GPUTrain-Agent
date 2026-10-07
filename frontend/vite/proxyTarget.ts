export function resolveControlPlaneOrigin(value: string | undefined): string {
  const raw = value?.trim() || 'http://localhost:8080'
  const url = new URL(raw)
  const loopback = ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)
  if ((url.protocol !== 'https:' && !(url.protocol === 'http:' && loopback)) ||
      url.username || url.password || (url.pathname !== '/' && url.pathname !== '') ||
      url.search || url.hash) {
    throw new Error('GPUTRAIN_CONTROL_PLANE_URL must be an HTTPS origin or loopback HTTP origin')
  }
  return url.origin
}
