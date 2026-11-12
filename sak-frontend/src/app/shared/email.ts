// one @ with something before it, a domain with a dot after it, no spaces (UserService.isEmail)
export function validEmail(email: string): boolean {
  const address = email.trim();
  return address.length <= 254 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(address);
}
