// Test code by its directory ("test") and its name (".spec.ts").
import { Server } from "../src/server";

describe("server", () => {
  it("starts", () => {
    new Server(1).start();
  });
});
