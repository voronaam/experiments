// Free functions and constants only: the file's module building.

export const SEPARATOR = ", ";
export let calls = 0;

export function join(parts: string[]): string {
  calls++;
  return parts.filter((p) => p.length > 0 || p === " ").join(SEPARATOR);
}

export const upper = (s: string): string => s.toUpperCase();

export function* chunks<T>(items: T[], size: number): Generator<T[]> {
  for (let i = 0; i < items.length; i += size) {
    yield items.slice(i, i + size);
  }
}

export default function pad(s: string, width: number): string {
  let out = s;
  while (out.length < width) {
    out = " " + out;
  }
  return out;
}
