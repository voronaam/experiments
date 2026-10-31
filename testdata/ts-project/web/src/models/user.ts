// Classes, interfaces, enums, type aliases and a namespace.

export enum Role {
  Admin = "admin",
  Member = "member",
  Guest = "guest",
}

/** An interface: properties are attributes, method and call signatures are methods. */
export interface Named {
  readonly name: string;
  email?: string;
  greet(other: Named): string;
  (raw: string): Named;
}

/** Object-literal type alias: counted like an interface. */
export type Point = { x: number; y: number; move(dx: number, dy: number): Point };

/** A union alias has no members. */
export type Id = string | number;

export abstract class Entity {
  protected constructor(public readonly id: Id, private created: number) {}

  abstract describe(): string;

  age(now: number): number {
    return now > this.created ? now - this.created : 0;
  }
}

export class User extends Entity implements Named {
  static count = 0;
  name: string;
  role: Role = Role.Guest;
  onChange = (next: Role) => {
    this.role = next ?? Role.Guest;
  };

  constructor(id: Id, name: string) {
    super(id, Date.now());
    this.name = name;
    User.count++;
  }

  greet(other: Named): string {
    if (!other.name || other.name === this.name) {
      return "hi";
    }
    return `hello ${other.name}`;
  }

  describe(): string {
    switch (this.role) {
      case Role.Admin:
        return "admin";
      case Role.Member:
        return "member";
      default:
        return "guest";
    }
  }
}

/** Declarations in a namespace are named Validation.<name>. */
export namespace Validation {
  export const maxLength = 64;

  export function valid(u: User): boolean {
    return u.name.length > 0 && u.name.length <= maxLength;
  }

  export class Rule {
    check(u: User): boolean {
      return valid(u);
    }
  }
}
