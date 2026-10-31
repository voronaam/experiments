import { load } from "./api";

test("loads", async () => {
  expect(await load("/none")).toEqual([]);
});
