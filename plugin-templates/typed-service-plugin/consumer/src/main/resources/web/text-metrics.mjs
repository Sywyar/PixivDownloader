import { createClient } from './generated/com.example.textmetrics.api.TextMetrics.mjs';

/**
 * @param {import('./generated/com.example.textmetrics.api.TextMetrics.mjs').analyze_Request} request
 * @param {{signal?: AbortSignal}} [options]
 * @param {typeof fetch} [fetcher]
 */
export function analyzeTexts(request, options, fetcher = globalThis.fetch) {
  return createClient(fetcher).analyze(request, options);
}
