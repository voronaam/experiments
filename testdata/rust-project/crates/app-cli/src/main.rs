//! A binary crate ("app-cli" becomes app_cli) using the library through absolute paths, a glob and an alias.

mod broken;

use geo::shapes::{Point as P, Shape};
use geo::util::*;
use geo::Area;

macro_rules! square {
    ($x:expr) => {
        $x * $x
    };
}

/// A command parsed from the arguments.
enum Command {
    Measure(f64),
    Help,
}

struct Config {
    verbose: bool,
    command: Command,
}

impl Config {
    fn parse(args: &[String]) -> Result<Config, String> {
        let mut verbose = false;
        let mut command = Command::Help;
        for arg in args {
            match arg.as_str() {
                "-v" | "--verbose" => verbose = true,
                "help" => command = Command::Help,
                other => {
                    let r: f64 = other.parse().map_err(|_| format!("bad radius {other}"))?;
                    command = Command::Measure(r);
                }
            }
        }
        Ok(Config { verbose, command })
    }
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let config = Config::parse(&args).unwrap_or(Config { verbose: false, command: Command::Help });
    let mut tries = 0;
    loop {
        tries += 1;
        if tries > 3 || config.verbose {
            break;
        }
    }
    if let Command::Measure(r) = config.command {
        let shape = Shape::circle(P::default(), clamp(r, 0.0, 10.0));
        println!("{} {}", shape.area(), square!(r) * PI);
    }
}
