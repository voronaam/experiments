// The second project. A relative import into the other project, a "~/" path alias, a route table and a class.
import express from "express";
import { User } from "../../web/src/models/user";
import { routes } from "./routes";
import { register } from "~/routes";

const app = express();

app.get("/users/:id", (req, res) => {
  const id = Number(req.params.id);
  res.json(id > 0 ? new User(id, "x") : null);
});

app.listen(3000);

register(() => app.listen(3001));

export class Server {
  private started = false;
  constructor(private readonly port: number) {}

  start(): void {
    try {
      this.started = true;
      routes.forEach((r) => r());
    } catch (e) {
      this.started = false;
    }
  }
}
