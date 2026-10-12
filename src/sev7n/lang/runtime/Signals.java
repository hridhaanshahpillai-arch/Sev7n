package sev7n.lang.runtime;

/**
 * Control-flow signals used to unwind the interpreter for return / break / continue.
 * They skip stack-trace capture because they're thrown constantly and never shown.
 */
public final class Signals {

    private Signals() { }

    public static final class Return extends RuntimeException {
        public final Object value;

        public Return(Object value) {
            super(null, null, false, false);
            this.value = value;
        }
    }

    public static final class Break extends RuntimeException {
        public static final Break INSTANCE = new Break();

        private Break() {
            super(null, null, false, false);
        }
    }

    public static final class Continue extends RuntimeException {
        public static final Continue INSTANCE = new Continue();

        private Continue() {
            super(null, null, false, false);
        }
    }
}
