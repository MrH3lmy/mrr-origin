export function formatCurrency(amount: number, currency = "USD"): string {
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency,
    maximumFractionDigits: 0,
  }).format(amount);
}

/**
 * Formats an integer minor-unit amount (as stored by the API, e.g. cents) as whole-currency-unit
 * text. Does not special-case zero-decimal currencies (e.g. JPY) -- V1 has no currency-specific
 * decimal-digit table anywhere in the stack yet.
 */
export function formatMoneyMinor(
  amountMinor: number,
  currency: string,
): string {
  return formatCurrency(amountMinor / 100, currency);
}
