package org.jruby.compiler.ir.instructions;

import org.jruby.compiler.ir.Operation;
import org.jruby.compiler.ir.operands.Operand;
import org.jruby.compiler.ir.operands.Variable;
import org.jruby.compiler.ir.operands.MetaObject;
import org.jruby.compiler.ir.IR_ExecutionScope;

public class STORE_TO_FRAME_Instr extends PUT_Instr
{
    public STORE_TO_FRAME_Instr(IR_ExecutionScope scope, String slotName, Operand value)
    {
        super(Operation.FRAME_STORE, new MetaObject(scope), slotName, new MetaObject(scope));
    }
}
