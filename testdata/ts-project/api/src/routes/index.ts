// Imported as "./routes" (a directory index).
export const routes: Array<() => void> = [];

export function register(route: () => void): void {
  if (!routes.includes(route)) routes.push(route);
}
