// Test code by its name; top-level calls with callbacks become methods.
import { join, upper } from "./format";

describe("format", () => {
  it("joins", () => {
    expect(join(["a", "b"])).toBe("a, b");
  });
});

// A label longer than 48 characters is cut, with an ellipsis.
it(`upper-cases everything, even with a label far longer than forty-eight characters`, () => {
  expect(upper("a") || upper("b")).toBe("A");
});
