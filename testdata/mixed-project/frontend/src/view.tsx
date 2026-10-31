import { Item, load } from "./api";

export function List({ items }: { items: Item[] }) {
  return <ul>{items.map((i) => <li key={i.id}>{i.name}</li>)}</ul>;
}

export const refresh = () => load("/items");
