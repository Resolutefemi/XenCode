## Getting errors when installing software?

This is caused by hard links being unavailable inside the container; fix it by adding this proot parameter:

```
--link2symlink
```

Edit the image -> proot parameters -> add -> save, then re-enter the terminal.

> Images imported via download already include this parameter by default.

## Terminal keys misbehaving (arrow keys showing ^[[A etc.)?

The current shell is sh, which has no line editing. Edit the container and change the shell to:

```
/bin/bash
```

bash has arrow keys, history, and completion built in.
