# Network tool plug-ins

RingMASTER loads extra tools from JSON files in the `plugins` folder next to `profiles.json`.

That folder is `%USERPROFILE%\.config\ringmaster\plugins` unless `XDG_CONFIG_HOME` is set to an absolute path. In that case the folder is `$XDG_CONFIG_HOME/ringmaster/plugins`.

Each `*.json` file is one tool. RingMASTER does not run a shell. `command` is a list of arguments. The first entry is a program name on `PATH` or an absolute path. A `{placeholder}` is replaced inside a single argument from the input with the same key. Values are never joined into one shell string.

RingMASTER shows the exact argument list and asks again whenever the command in the file, or any argument, differs from one you confirmed in this session. The first line is the program file that will run: a name is shown as the file found on `PATH`. If `PATH` later finds a different file, RingMASTER asks again.

A line longer than 8192 characters is cut. A tool that prints more than 4 MB is stopped.

Batch files (.bat, .cmd) are refused because Windows passes their arguments to cmd.exe.

A file that is not valid is listed on the Network tools page and is not run.

## Fields

- `id`: lowercase letters, digits and hyphens. Built-in ids (`ping`, `traceroute`, `port-check`, `dns`, `http-tls`, `iperf3`, `wireguard`) are reserved.
- `name` and `summary`: short text shown in the list.
- `command`: array of strings. Use `{key}` where an input should go. The program itself cannot be a placeholder.
- `inputs`: array of objects with `key`, `label`, `kind`, and optional `default`, `hint`, `min`, `max`, `choices`, `required`.
  `kind` is `Text`, `Host`, `Port`, `Number`, `Choice` or `Toggle`.
  Host values must be a hostname or an IP address. Ports are 1 to 65535. Numbers must sit inside `min` and `max`.
- `lines`: optional regular expression applied to the first 1000 characters of each output line. A named group `value` becomes a chart sample. Optional named groups are `series`, `x` and `unit`. A pattern that takes too long on a line is skipped for that line, and after three such lines the chart is off for the run.

See `example-curl.json`.
