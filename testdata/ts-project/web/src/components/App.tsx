import { useState } from "react";
import { Button } from "./Button";
import { join } from "@/lib/format";
import { VERSION } from "../lib";
import { User, Role } from "@/models/user";
import pad from "../lib/format";

/** A function component; the default export. */
export default function App({ users }: { users: User[] }) {
  const [count, setCount] = useState(0);
  const admins = users.filter((u) => u.role === Role.Admin);
  if (admins.length === 0) {
    return <p>no admins</p>;
  }
  return (
    <div>
      <h1>{pad(VERSION, 4)}</h1>
      {admins.map((a) => (
        <Button key={a.name} label={join([a.name, String(count)])} onClick={() => setCount(count + 1)} />
      ))}
    </div>
  );
}

/** A class component. */
export class Counter extends React.Component<{ start: number }> {
  state = { value: this.props.start };

  render() {
    return <span>{this.state.value > 0 ? this.state.value : "none"}</span>;
  }
}
