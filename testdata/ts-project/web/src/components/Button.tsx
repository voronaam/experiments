import React from "react";
import { upper } from "../lib/format";

export interface ButtonProps {
  label: string;
  primary?: boolean;
  onClick?: () => void;
}

/** A React function component written as an arrow function. */
export const Button = ({ label, primary, onClick }: ButtonProps) => (
  <button className={primary ? "primary" : "plain"} onClick={onClick}>
    {primary && <strong>!</strong>}
    {upper(label)}
  </button>
);
