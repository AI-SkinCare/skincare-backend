/**
 * TRD §9.1 [앱 협의] — 리프레시 토큰을 응답 본문으로도 받는 네이티브 앱.
 * Android를 만들면 'android'를 여기에만 추가한다 (TRD 표를 먼저 고칠 것).
 */
export const NATIVE_CLIENTS = ['ios'] as const;
export type NativeClient = (typeof NATIVE_CLIENTS)[number];

export const CLIENT_HEADER = 'x-client';

export function isNativeClient(header: unknown): header is string {
  if (typeof header !== 'string') return false;
  return (NATIVE_CLIENTS as readonly string[]).includes(header.trim().toLowerCase());
}
