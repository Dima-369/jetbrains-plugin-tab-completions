# AI Slop below

## Local Sweep — JetBrains FIM Plugin

A JetBrains plugin providing inline code completions via a local FIM (Fill-in-the-Middle) server.

### Model Choice

This plugin uses the Qwen 2.5 Coder FIM format (`<|fim_prefix|>...<|fim_suffix|>...<|fim_middle|>`).

**Use the base model, not instruct.** The Qwen team confirmed that the **base** model (`Qwen2.5-Coder-7B`) is the one properly trained for FIM tasks. The instruct model is fine-tuned for chat and is not recommended for FIM.

The base model also supports **repo-level FIM** with cross-file context using special tokens:

```
<|repo_name|>{repo_name}
<|file_sep|>{file_path1}
{file_content1}
<|file_sep|>{file_path2}
{file_content2}
<|file_sep|>{file_path3}
<|fim_prefix|>{code_pre}<|fim_suffix|>{code_suf}<|fim_middle|>
```

This allows prepending contents of other open files before the FIM block to improve completion quality. The instruct model was not trained with this repo-level format.

### Architecture

- **FimClient** — Sends FIM requests to a local llama.cpp server at `http://localhost:8095/completion`
- **FimCompletionProvider** — JetBrains inline completion provider; sends up to 3000 chars before and 1500 chars after the cursor
