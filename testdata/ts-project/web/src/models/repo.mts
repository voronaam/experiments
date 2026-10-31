// An .mts file that imports with an ESM-style ".js" specifier, which means "./user.ts".
import { User, type Named } from "./user.js";
import * as models from "./user.js";

export class Repo<T extends Named> {
  private items = new Map<string, T>();

  add(item: T): void {
    this.items.set(item.name, item);
  }

  find(name: string): T | undefined {
    for (const [key, value] of this.items) {
      if (key === name) return value;
    }
    return undefined;
  }
}

export const users = new Repo<User>();
export const guest = new models.User(0, "guest");
