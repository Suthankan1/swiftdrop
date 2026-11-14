/** Safe reads recover twice; authentication and validation failures never retry. */
export function merchantReadRetry(failureCount: number, error: unknown): boolean {
  return failureCount < 2 && typeof error === 'object' && error !== null &&
    'kind' in error && ['unavailable', 'timeout'].includes(String(error.kind))
}
