# OpenCode-Plugin Project Rules

## About

This repo is a JetBrains IDE plugin that integrates the OpenCode AI coding assistant into IntelliJ-based IDEs. It
provides a tool window, settings panel, and lifecycle management for launching and interacting with the OpenCode CLI
from within the IDE.

## Prefer JetBrains Tools

When searching the code base or attempting to run a build
Take a look at the available run configurations(`webstorm_get_run_configurations`)
Always prefer `jetbrains_*` tools over
generic alternatives when both are available:

| Task                  | Prefer                                                                      | Over                       |
|-----------------------|-----------------------------------------------------------------------------|----------------------------|
| Read a file           | `jetbrains_get_file_text_by_path`                                           | `Read` / `cat`             |
| Create a file         | `jetbrains_create_new_file`                                                 | `Write` / `echo`           |
| Find files by name    | `jetbrains_find_files_by_name_keyword`                                      | `Glob` / `find`            |
| Find files by pattern | `jetbrains_find_files_by_glob`                                              | `Glob` / `find`            |
| Search content        | `jetbrains_search_in_files_by_text` or `jetbrains_search_in_files_by_regex` | `Grep` / `rg` / `grep`     |
| Browse directory      | `jetbrains_list_directory_tree`                                             | `ls` / `find`              |
| Rename symbols        | `jetbrains_rename_refactoring`                                              | text search-replace        |
| Check file errors     | `jetbrains_get_file_problems`                                               | manual inspection          |
| Build project         | `jetbrains_build_project`                                                   | `./gradlew build` via Bash |