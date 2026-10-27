// labels of letters, digits and hyphens, at least two of them, as the controller accepts them
export const DOMAIN_PATTERN = /^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/i;

// the domains entered, without blank ones
export function enteredDomains(domains: string[]): string[] {
  return domains.map((domain) => domain.trim()).filter((domain) => domain !== '');
}

export function validDomains(domains: string[]): boolean {
  return enteredDomains(domains).every((domain) => DOMAIN_PATTERN.test(domain));
}
