// Test code by its directory and its name.
import App from "../components/App";

test("renders", () => {
  const out = App({ users: [] });
  expect(out).toBeTruthy();
});
