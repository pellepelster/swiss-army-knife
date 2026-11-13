// one @ with something before it, a domain with a dot after it, no spaces (UserService.isEmail): the
// address of a user an admin adds
export function validEmail(email: string): boolean {
  const address = email.trim();
  return address.length <= 254 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(address);
}

// one @ with something on both sides, no spaces: the address a login link is asked for, which the
// backend takes as an address of the mail (normalizeEmail), also one without a dot in its domain,
// e.g. an admin of the configuration at admin@localhost
export function validLoginEmail(email: string): boolean {
  const address = email.trim();
  return address.length <= 254 && /^[^\s@]+@[^\s@]+$/.test(address);
}
