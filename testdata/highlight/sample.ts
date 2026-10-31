import { Thing } from './thing';

export const MAX_ITEMS = 1_000;
type Id = string | number;

@Component({ selector: 'app' })
export class Widget<T> extends Thing implements Base {
  #secret = 42n;
  private readonly label: string = `hello ${name}
world`;
  constructor(private id: Id) { super(); }
  /* block
     comment */ render(): void { console.log("it's", 'x', 3.14); }
}
