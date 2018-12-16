package io.jitstatic.storage;

import static java.nio.charset.StandardCharsets.UTF_8;

/*-
 * #%L
 * jitstatic
 * %%
 * Copyright (C) 2017 - 2018 H.Hegardt
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;

import io.jitstatic.storage.Tree.Branch;
import io.jitstatic.storage.Tree.Leaf;

public class PrintingVisitor implements TreeVisitor<Void> {

    private static final byte[] BRANCHES;
    private static final byte[] LEAFS;
    private static final byte[] DASH;
    private static final byte[] NL;
    private final int i;
    private final OutputStream os;
    private final byte[] indent;

    static {
        BRANCHES = ("|-").getBytes(UTF_8);
        LEAFS = ("|--").getBytes(UTF_8);
        DASH = new byte[] { '/' };
        NL = System.lineSeparator().getBytes(UTF_8);
    }

    public PrintingVisitor(final OutputStream os) {
        this(0, os);
    }

    PrintingVisitor(int i, final OutputStream os) {
        this.i = i;
        this.os = os;
        byte[] id = new byte[i];
        Arrays.fill(id, (byte) ' ');
        this.indent = id;
    }

    @Override
    public Void visit(final Tree tree) {
        printTree(tree);
        printBody(tree);
        return null;
    }

    @Override
    public Void visit(Branch tree) {
        printBranchHeader(tree);
        printBody(tree);
        return null;
    }

    @Override
    public Void visit(Leaf tree) {
        printLeafHeader(tree);
        printBody(tree);
        return null;
    }

    private void printBranchHeader(Branch tree) {
        write((tree.getName()).getBytes(UTF_8));
        write(DASH);
        write(NL);
    }

    private void printLeafHeader(Leaf tree) {
        printTree(tree);
    }

    private void printBody(Tree tree) {
        tree.getBranches().stream().forEach(b -> {
            write(indent);
            write(BRANCHES);
            printBranch(b);
        });
        tree.getLeafs().stream().forEach(l -> {
            write(indent);
            write(LEAFS);
            printLeaf(l);
        });
    }

    private void write(byte[] indent2) {
        try {
            os.write(indent2);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void printTree(Tree tree) {
        write((tree.getName()).getBytes(UTF_8));
        write(NL);
    }

    private void printBranch(Tree branch) {
        branch.accept(new PrintingVisitor(i + 4, os));
    }

    private void printLeaf(Tree leaf) {
        leaf.accept(new PrintingVisitor(i + 4, os));
    }

}
