// Outside both projects: the root module "".
import { join } from "../web/src/lib/format";

function main(args: string[]): number {
  return args.length > 1 && args[0] === "--watch" ? 1 : join(args).length;
}

main(process.argv);
