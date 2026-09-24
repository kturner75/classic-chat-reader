package com.classicchatreader.cli;

import com.classicchatreader.style.StyleTransfer;
import java.io.PrintStream;

/** Book art style export/replace for Studio production ship. Never touches image files or Spaces. */
public final class StyleTransferRunner {
    private static final BookTransferCli.Transfer<StyleTransfer.Plan, StyleTransfer.Snapshot> TRANSFER =
            new BookTransferCli.Transfer<>("Book style", "style-", StyleTransfer.Plan.class,
                    StyleTransfer.Plan::source, StyleTransfer.Plan::sourceId,
                    StyleTransfer::exportStyle, StyleTransfer::apply, StyleTransfer.StalePlan.class);
    private StyleTransferRunner() {}
    public static void main(String[] args) {
        int code = run(args, System.out, System.err);
        if (code != 0) System.exit(code);
    }
    static int run(String[] args, PrintStream out, PrintStream err) {
        return BookTransferCli.run(args, out, err, TRANSFER);
    }
}
