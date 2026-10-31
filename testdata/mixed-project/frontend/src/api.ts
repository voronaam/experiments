// The TypeScript part: no package.json, so the root module "".
export interface Item {
  id: number;
  name: string;
}

export async function load(url: string): Promise<Item[]> {
  const res = await fetch(url);
  return res.ok ? res.json() : [];
}
