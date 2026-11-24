// Reproduces the Kotlin coroutine suspension protocol that breaks when a
// suspend function is converted to native code.
//
// The shape mirrors the real smali bytecode of
//   com.google.android.apps.nexora.screens.games.GamesScreenKt
//       .GamesScreen$refreshGates(Context, I.e0, I.e0, Continuation)
// and of the generated continuation class' invokeSuspend:
//
//   invokeSuspend(result):            refreshGates(...):
//     this.result = result              if (c instanceof Cont && (c.label & MIN) != 0)
//     this.label |= MIN                   c.label -= MIN
//     return refreshGates(...)          else c = new Cont()
//                                       ...
//                                       c.label = 1
//                                       r = call()      // suspends
//                                       if (r == COROUTINE_SUSPENDED) return r
//                                       switch (c.label) { 0 -> start, 1 -> resume }
//
// If the native translation loses reference identity of the suspension
// sentinel, or mishandles the MIN_VALUE state bit, the resume path is either
// never taken (function restarts) or the state machine throws
// "call to 'resume' before 'invoke' with coroutine".
public class Test {

    static final Object COROUTINE_SUSPENDED = new Object();
    static final int MIN = Integer.MIN_VALUE;

    static class Cont {
        int label;
        Object result;
        Object L0;
        Object L1;
        Cont outer;

        Cont() {
        }

        Cont(Cont outer) {
            this.outer = outer;
        }
    }

    /** Stands in for SetupGates.snapshot(), which suspends on Dispatchers.IO. */
    static Object snapshot(Cont c) {
        return COROUTINE_SUSPENDED;
    }

    static Object refreshGates(String a, String b, Cont c) {
        if (c != null && (c.label & MIN) != 0) {
            c.label -= MIN;
        } else {
            c = new Cont(c);
        }

        Object result = c.result;
        Object sentinel = COROUTINE_SUSPENDED;
        int label = c.label;

        if (label == 0) {
            c.L0 = a;
            c.L1 = b;
            c.label = 1;
            Object r = snapshot(c);
            if (r == sentinel) {
                return sentinel;
            }
            c.result = r;
        } else if (label != 1) {
            throw new IllegalStateException("call to 'resume' before 'invoke' with coroutine");
        }

        String s = (String) c.result;
        return "done:" + s + "/" + c.L0 + "/" + c.L1;
    }

    /** Mirrors the generated continuation class' invokeSuspend. */
    static Object invokeSuspend(Object outcome, Cont c) {
        c.result = outcome;
        c.label |= MIN;
        return refreshGates("A", "B", c);
    }

    /** Mirrors the nested suspend function that does the actual offloading. */
    static Object outerSuspend(Cont c) {
        if (c != null && (c.label & MIN) != 0) {
            c.label -= MIN;
        } else {
            c = new Cont(c);
        }

        Object sentinel = COROUTINE_SUSPENDED;
        int label = c.label;

        if (label == 0) {
            c.label = 1;
            Object r = refreshGates("X", "Y", new Cont(c));
            if (r == sentinel) {
                return sentinel;
            }
            c.result = r;
        } else if (label != 1) {
            throw new IllegalStateException("call to 'resume' before 'invoke' with coroutine");
        }

        return "outer:" + c.result;
    }

    static Object invokeOuter(Object outcome, Cont c) {
        c.result = outcome;
        c.label |= MIN;
        return outerSuspend(c);
    }

    public static void main(String[] args) throws Exception {
        // 1. suspend on the main thread, resume on a worker thread
        Cont c = new Cont();
        Object r1 = invokeSuspend(null, c);
        System.out.println(r1 == COROUTINE_SUSPENDED ? "suspended" : r1);

        Thread worker = new Thread(() -> {
            Object r2 = invokeSuspend("VALUE", c);
            System.out.println(r2 == COROUTINE_SUSPENDED ? "suspended-again" : r2);
        });
        worker.start();
        worker.join();

        // 2. nested suspend, resumed from two different threads in order
        Cont o = new Cont();
        Object o1 = invokeOuter(null, o);
        System.out.println(o1 == COROUTINE_SUSPENDED ? "outer-suspended" : o1);

        Thread w1 = new Thread(() -> {
            Object o2 = invokeOuter("INNER", o);
            System.out.println(o2 == COROUTINE_SUSPENDED ? "outer-suspended-again" : o2);
        });
        w1.start();
        w1.join();

        System.out.println("ok");
    }
}
