# Checkpoints & Undo

Before the AI performs file modification operations, XenCode automatically saves snapshots of the files about to change and creates a checkpoint. If the generated result misses the mark or introduces unexpected problems, you can rewind to a given point at any time with one tap. The mechanism runs fully automatically and needs no manual intervention.

## How to Roll Back

In the chat history, find the **message you sent** that you want to go back to; it has an undo icon on its left. Tap it to open that checkpoint's action menu.

The menu has three choices:

| Action | Code | Conversation |
| --- | --- | --- |
| Restore code and conversation | Return to the state before this message | Delete the messages after this one and refill the original prompt into the input box |
| Restore conversation only | Leave everything as is | Delete the messages after this one |
| Restore code only | Return to the state before this message | Keep everything intact |

The first is the most common: it amounts to "redo this round"—code and conversation both return to the starting point, and you can revise your prompt and send it again.

Restoring the conversation deletes the selected message and the ones after it, discards the existing compression summary, and restores the original conversation retained before the rewind point, preventing the summary from carrying later facts forward. "Restore code only" does not touch the conversation. If the restored history is very long, it is compressed again before the next send; usage already incurred is not reset. Content that was already truncated or missing in older versions cannot be recovered by rewinding.

If you just want the AI to forget a few rounds of discussion that went off track but keep the code it already wrote, use "Restore conversation only". Conversely, to throw the code away but keep the discussion for review, use "Restore code only".

## Scope and Limitations

Checkpoints only record modifications the AI makes **through file tools**. If a file was changed or deleted by a command in the terminal (`rm`, `mv`, `sed` and the like—whether you typed it or the AI ran it), checkpoints cannot restore it precisely.

In that case, restoring via Git is recommended: as long as the file was ever tracked by Git (committed once is enough), go to the Status tab on the Git page, long-press the file, and choose "Restore file" or "Revert changes" to get it back—regardless of how it was deleted. Conversely, new files that were never committed are outside this safety net.

For important projects, manage them with Git as well. Checkpoints are good for rolling back a single round of changes; Git is the complete version-control safety net.
