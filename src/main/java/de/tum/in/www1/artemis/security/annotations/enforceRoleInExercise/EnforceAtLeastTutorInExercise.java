package de.tum.in.www1.artemis.security.annotations.enforceRoleInExercise;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.core.annotation.AliasFor;
import org.springframework.security.access.prepost.PreAuthorize;

import de.tum.in.www1.artemis.security.Role;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasRole('TA')")
@EnforceRoleInExercise(Role.TEACHING_ASSISTANT)
public @interface EnforceAtLeastTutorInExercise {

    /**
     * The name of the field in the method parameters that contains the exercise id.
     * This is used to extract the exercise id from the method parameters
     *
     * @return the name of the field in the method parameters that contains the exercise id
     */
    @AliasFor(annotation = EnforceRoleInExercise.class)
    String resourceIdFieldName() default "exerciseId";
}
