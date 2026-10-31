#!/bin/sh
# Builds the git history fixture of the end-to-end tests (all three editions) in <dir>, which must not exist yet or be
# empty. Usage:
#
#   sh testdata/make-history-repo.sh <dir>
#
# Every commit has fixed author and committer names, e-mails and dates, and the user's git configuration is not read,
# so the commit ids are the same on every machine and the golden files can include them. The history covers all three
# languages and every kind of change the history metrics and the replay have to follow:
#
#   1  2021-01-04  Ann  add Order.java and web/cart.ts                              (adds, two languages)
#   2  2021-02-01  Bob  add the ledger crate, grow Order                            (adds Rust, modifies Java)
#   3  2021-03-01  Ann  add Customer.java, add an interface to cart.ts              (adds a file and a type)
#   4  2021-04-01  Cid  rename web/cart.ts to web/basket.ts                         (rename: path-based ids change)
#   5  2021-05-01  Bob  on branch "feature": ledger entries                         (not on the first-parent line)
#   6  2021-05-02  Bob  delete Customer.java                                        (a type disappears: "gone")
#   7  2021-05-03  Ann  merge "feature"                                             (merge commit, brings in 5)
#   8  2021-06-01  Ann  add OrderTest.java with another e-mail, grow Order again    (test code, same author name)
#
# The committer is always "CI Bot", one hour after the author date, so a test can tell author data from committer
# data. Only POSIX sh and git are needed.
set -e
if [ $# -ne 1 ]; then
    echo "usage: sh make-history-repo.sh <dir>" >&2
    exit 2
fi
mkdir -p "$1"
cd "$1"

# Ignore the user's and the system's git configuration (signing, hooks, templates, default branch, line endings).
export GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null HOME=/nonexistent
export GIT_COMMITTER_NAME="CI Bot" GIT_COMMITTER_EMAIL=ci@example.com
git init -q --template= .
git symbolic-ref HEAD refs/heads/main
git config commit.gpgsign false
git config core.autocrlf false

# commit <author name> <author e-mail> <day as YYYY-MM-DD> <subject>: commits everything staged and unstaged.
commit() {
    git add -A
    GIT_AUTHOR_NAME="$1" GIT_AUTHOR_EMAIL="$2" GIT_AUTHOR_DATE="$3T10:00:00 +0000" \
        GIT_COMMITTER_DATE="$3T11:00:00 +0000" git commit -q -m "$4"
}

# 1: Java and TypeScript
mkdir -p app/src/main/java/demo web
cat > app/src/main/java/demo/Order.java <<'EOF'
package demo;

public class Order {
    private int id;

    public int total() {
        return id > 0 ? id : 0;
    }
}
EOF
cat > web/cart.ts <<'EOF'
export class Cart {
  items: string[] = [];

  add(item: string): void {
    this.items.push(item);
  }
}

export function price(n: number): number {
  return n > 10 ? n * 0.9 : n;
}
EOF
commit "Ann Author" ann@example.com 2021-01-04 "Add order and cart"

# 2: a Rust crate (its manifest comes with its first source file, so the crate is never named after the directory)
mkdir -p ledger/src
cat > ledger/Cargo.toml <<'EOF'
[package]
name = "ledger"
version = "0.1.0"
EOF
cat > ledger/src/lib.rs <<'EOF'
pub struct Ledger {
    pub balance: i64,
}

impl Ledger {
    pub fn post(&mut self, amount: i64) {
        self.balance += amount;
    }
}
EOF
cat > app/src/main/java/demo/Order.java <<'EOF'
package demo;

public class Order {
    private int id;
    private Customer customer;

    public int total() {
        return id > 0 ? id : 0;
    }

    public boolean valid() {
        return id > 0 && customer != null;
    }
}
EOF
commit "Bob Builder" bob@example.com 2021-02-01 "Add the ledger crate"

# 3: a new Java class and a new TypeScript interface
cat > app/src/main/java/demo/Customer.java <<'EOF'
package demo;

public class Customer {
    private String name;
    private String email;

    public String label() {
        return name == null ? email : name;
    }
}
EOF
cat >> web/cart.ts <<'EOF'

export interface Line {
  item: string;
  count: number;
}
EOF
commit "Ann Author" ann@example.com 2021-03-01 "Add customers and cart lines"

# 4: a rename; the TypeScript ids contain the path, so Cart and Line become new buildings
git mv web/cart.ts web/basket.ts
commit "Cid Coder" cid@example.com 2021-04-01 "Rename cart to basket"

# 5: a side branch, merged in 7; the replay samples the first-parent line only
git checkout -q -b feature
cat > ledger/src/lib.rs <<'EOF'
pub struct Ledger {
    pub balance: i64,
    pub entries: Vec<Entry>,
}

pub enum Entry {
    Credit(i64),
    Debit(i64),
}

impl Ledger {
    pub fn post(&mut self, entry: Entry) {
        match entry {
            Entry::Credit(a) => self.balance += a,
            Entry::Debit(a) => self.balance -= a,
        }
        self.entries.push(entry);
    }
}
EOF
commit "Bob Builder" bob@example.com 2021-05-01 "Ledger entries"
git checkout -q main

# 6: a deletion; Customer only exists in the past now
git rm -q app/src/main/java/demo/Customer.java
cat > app/src/main/java/demo/Order.java <<'EOF'
package demo;

public class Order {
    private int id;

    public int total() {
        return id > 0 ? id : 0;
    }

    public boolean valid() {
        return id > 0;
    }
}
EOF
commit "Bob Builder" bob@example.com 2021-05-02 "Drop customers"

# 7: the merge commit
GIT_AUTHOR_NAME="Ann Author" GIT_AUTHOR_EMAIL=ann@example.com GIT_AUTHOR_DATE="2021-05-03T10:00:00 +0000" \
    GIT_COMMITTER_DATE="2021-05-03T11:00:00 +0000" git merge -q --no-ff -m "Merge ledger entries" feature
git branch -q -D feature

# 8: test code, by the same author under another e-mail address
mkdir -p app/src/test/java/demo
cat > app/src/test/java/demo/OrderTest.java <<'EOF'
package demo;

class OrderTest {
    void total() {
        assert new Order().total() == 0;
    }
}
EOF
cat > app/src/main/java/demo/Order.java <<'EOF'
package demo;

public class Order {
    private int id;
    private int discount;

    public int total() {
        return id > 0 ? id - discount : 0;
    }

    public boolean valid() {
        return id > 0 || discount < 0;
    }
}
EOF
commit "Ann Author" ann@work.example 2021-06-01 "Test orders"
