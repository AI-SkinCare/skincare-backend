/** TRD §7.6 — `dohee@gmail.com` → `d***@gmail.com` */
export function maskEmail(email: string): string {
  const at = email.lastIndexOf('@');
  if (at <= 0) return '***';
  return `${email[0]}***${email.slice(at)}`;
}
