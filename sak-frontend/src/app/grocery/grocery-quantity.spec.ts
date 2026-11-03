import { parseAmount } from './grocery-quantity';

describe('parseAmount', () => {
  it('takes a positive number with a point or a comma and at most 3 decimal places, as android does', () => {
    expect(parseAmount('')).toBeNull();
    expect(parseAmount('  ')).toBeNull();
    expect(parseAmount('2')).toBe(2);
    expect(parseAmount('0,5')).toBe(0.5);
    expect(parseAmount('0.125')).toBe(0.125);
    expect(parseAmount('.5')).toBe(0.5);
    expect(parseAmount('5.')).toBe(5);
    expect(parseAmount('9999999.999')).toBe(9999999.999);
  });

  it('refuses no number, zero, more decimals and more than the backend stores', () => {
    for (const typed of ['abc', '0', '0,0', '-1', '1.2345', '1,2.3', '10000000', '.']) {
      expect(parseAmount(typed)).toBeUndefined();
    }
  });
});
