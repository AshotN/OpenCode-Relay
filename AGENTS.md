# OpenCode-Plugin Project Rules

## About

This repo is a JetBrains IDE plugin that integrates the OpenCode AI coding assistant into IntelliJ-based IDEs. It provides a tool window, settings panel, and lifecycle management for launching and interacting with the OpenCode CLI from within the IDE.

## Prefer JetBrains Tools

Always prefer `jetbrains_*` tools over generic alternatives when both are available:

- Use `jetbrains_*` tools for all file reads/edits/creates. Do not use `Read`, `Write`, `apply_patch`, or shell-based file editing unless a JetBrains tool fails.

| Task | Prefer | Over |
|------|--------|------|
| Read a file | `jetbrains_get_file_text_by_path` | `Read` / `cat` |
| Edit a file | `jetbrains_replace_text_in_file` | `Edit` / `sed` |
| Create a file | `jetbrains_create_new_file` | `Write` / `echo` |
| Find files by name | `jetbrains_find_files_by_name_keyword` | `Glob` / `find` |
| Find files by pattern | `jetbrains_find_files_by_glob` | `Glob` / `find` |
| Search content | `jetbrains_search_in_files_by_text` or `jetbrains_search_in_files_by_regex` | `Grep` / `rg` / `grep` |
| Browse directory | `jetbrains_list_directory_tree` | `ls` / `find` |
| Run terminal commands | `jetbrains_execute_terminal_command` | `Bash` |
| Rename symbols | `jetbrains_rename_refactoring` | text search-replace |
| Check file errors | `jetbrains_get_file_problems` | manual inspection |
| Build project | `jetbrains_build_project` | `./gradlew build` via Bash |
