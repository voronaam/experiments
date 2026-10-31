// The project entry: imports through the directory index and a bare package.
import { VERSION } from "./lib";
import App from "./components/App";
import { createRoot } from "react-dom/client";

const root = createRoot(document.getElementById("root")!);
root.render(App({ users: [] }));
console.log(VERSION);
