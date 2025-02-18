package net.bytebuddy.description.method;

import org.junit.Ignore;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;

public class MethodDescriptionForLoadedTest extends AbstractMethodDescriptionTest {

    protected MethodDescription.InDefinedShape describe(Method method) {
        return new MethodDescription.ForLoadedMethod(method);
    }

    protected MethodDescription.InDefinedShape describe(Constructor<?> constructor) {
        return new MethodDescription.ForLoadedConstructor(constructor);
    }

    @Test
    public void testGetLoadedMethod() throws Exception {
        Method method = Object.class.getDeclaredMethod("toString");
        assertThat(new MethodDescription.ForLoadedMethod(method).getLoadedMethod(), sameInstance(method));
    }

    protected boolean canReadDebugInformation() {
        return false;
    }

    @Test
    @Override
    @Ignore("The JVM does not currently consider synthetic parameter indices for annotations")
    public void testEnumConstructorAnnotation() throws Exception {
        super.testEnumConstructorAnnotation();
    }
}
