// Autogenerated AST node
package org.python.parser.ast;

import org.python.parser.SimpleNode;
import java.io.DataOutputStream;
import java.io.IOException;

public class Continue extends stmtType {

    public Continue() {
    }

    public Continue(SimpleNode parent) {
        this();
        this.beginLine = parent.beginLine;
        this.beginColumn = parent.beginColumn;
    }

    public String toString() {
        StringBuffer sb = new StringBuffer("Continue[");
        sb.append("]");
        return sb.toString();
    }

    public void pickle(DataOutputStream ostream) throws IOException {
        pickleThis(27, ostream);
    }

    public Object accept(VisitorIF visitor) throws Exception {
        return visitor.visitContinue(this);
    }

    public void traverse(VisitorIF visitor) throws Exception {
    }

}