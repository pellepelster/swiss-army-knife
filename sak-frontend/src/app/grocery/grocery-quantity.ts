import { I18n } from '../i18n/i18n';

// the usual units in the order the list offers them; an item stores the label in the language of
// the user, e.g. EL (GROCERY-15)
const UNITS = [
  'g',
  'kg',
  'ml',
  'l',
  'tbsp',
  'tsp',
  'pinch',
  'pack',
  'can',
  'bunch',
  'piece',
] as const;

// the largest amount the backend stores
const MAX_AMOUNT = 9_999_999.999;

// one unit to choose: the text stored and the one shown, both the label
export interface UnitOption {
  value: string;
  label: string;
}

// the units to choose, a unit the item has that is not one of them after them, so it is not lost;
// no unit is the empty value before them
export function unitOptions(i18n: I18n, unit: string | null | undefined): UnitOption[] {
  const units = UNITS.map((key) => {
    const label = i18n.t(`grocery.quantity.units.${key}`);
    return { value: label, label };
  });
  if (unit != null && unit !== '' && !units.some((each) => each.value === unit)) {
    units.push({ value: unit, label: unit });
  }
  return units;
}

// the amount typed, with a point or a comma: null for none, undefined for one that is no positive
// number with at most 3 decimal places
export function parseAmount(text: string): number | null | undefined {
  const typed = text.trim();
  if (typed === '') return null;
  if (!/^(?:\d+(?:[.,]\d{0,3})?|[.,]\d{1,3})$/.test(typed)) return undefined;
  const amount = Number(typed.replace(',', '.'));
  return amount > 0 && amount <= MAX_AMOUNT ? amount : undefined;
}

// the amount in its field, as the row shows it
export function amountText(amount: number | null | undefined): string {
  return amount == null ? '' : String(amount);
}
