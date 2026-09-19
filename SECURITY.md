# Security policy

## Supported versions

Only the latest commit on `main` is supported. There are no maintained release branches before 1.0.

## Reporting a vulnerability

Report vulnerabilities through
[GitHub private vulnerability reporting](https://github.com/ball2jh/stride-simulator/security/advisories/new)
rather than a public issue. Include the version or commit, a description of the impact, and a minimal input
that reproduces the problem. You should hear back within a week. Fixes are published on `main` with a
`CHANGELOG.md` entry; credit is given unless you ask otherwise.

## Scope

This library has no network code and executes nothing from its inputs, but it parses trace files, world
captures and block catalogs that may come from untrusted sources. Parser bugs are in scope: a crafted file
that causes unbounded memory or CPU use, an out-of-bounds read, or a `WorldSnapshot` that passes
verification while describing something other than what its catalog says. Refusals, incorrect physics on
valid input and denial of service through legitimately large worlds are ordinary bugs; report them as
issues.
