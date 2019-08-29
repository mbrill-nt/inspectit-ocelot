package rocks.inspectit.ocelot.config.validation;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import rocks.inspectit.ocelot.config.utils.CaseUtils;

import java.beans.PropertyDescriptor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.*;

@Component
public class Helper {

    @Autowired
    CaseUtils utils;

    /**
     * A HashSet of classes which are used as wildcards in the search for properties. If a found class matches one of these
     * classes, the end of the property path is reached. Mainly used in the search of maps
     */
    private static final HashSet<Class<?>> TERMINAL_TYPES = new HashSet(Arrays.asList(Object.class, String.class, Integer.class, Long.class,
            Float.class, Double.class, Character.class, Void.class,
            Boolean.class, Byte.class, Short.class, Duration.class));

    /**
     * Checks if a given List of properties exists as path
     *
     * @param propertyNames The list of properties one wants to check
     * @param type          The type in which the current top-level properties should be found
     * @return True: when the property exsits <br> False: when it doesn't
     */
    //@VisibleForTesting
    public OrderEnum checkPropertyExists(List<String> propertyNames, Type type) {
        if (propertyNames.isEmpty()) {
            if (isTerminalOrEnum(type)) {
                return OrderEnum.EXISTS_PATH_END;
            } else {
                return OrderEnum.EXISTS_NON_PATH_END;
            }
        }
        if (type instanceof ParameterizedType) {
            ParameterizedType genericType = (ParameterizedType) type;
            if (genericType.getRawType() == Map.class) {
                return checkPropertyExistsInMap(propertyNames, genericType.getActualTypeArguments()[1]);
            } else if (genericType.getRawType() == List.class) {
                return checkPropertyExistsInList(propertyNames, genericType.getActualTypeArguments()[0]);
            }
        }
        if (type instanceof Class) {
            return checkPropertyExistsInBean(propertyNames, (Class<?>) type);
        } else {
            throw new IllegalArgumentException("Unexpected type: " + type);
        }
    }

    /**
     * Checks if a given type exists as value type in a map, keeps crawling through the given propertyName list
     *
     * @param propertyNames List of property names
     * @param mapValueType  The type which is given as value type of a map
     * @return True: The type exists <br> False: the type does not exists
     */
    // @VisibleForTesting
    OrderEnum checkPropertyExistsInMap(List<String> propertyNames, Type mapValueType) {
        if (isTerminalOrEnum(mapValueType)) {
            return OrderEnum.EXISTS_PATH_END;
        } else {
            return checkPropertyExists(propertyNames.subList(1, propertyNames.size()), mapValueType);
        }
    }

    /**
     * Checks if a given type exists as value type in a list, keeps crawling through the given propertyName list
     *
     * @param propertyNames List of property names
     * @param listValueType The type which is given as value type of a list
     * @return True: The type exists <br> False: the type does not exists
     */
    // @VisibleForTesting
    OrderEnum checkPropertyExistsInList(List<String> propertyNames, Type listValueType) {
        return checkPropertyExists(propertyNames.subList(1, propertyNames.size()), listValueType);
    }

    /**
     * Checks if the first entry of the propertyNames list exists as property in a given bean
     *
     * @param propertyNames List of property names
     * @param beanType      The bean through which should be searched
     * @return True: the property and all other properties exists <br> False: At least one of the properties does not exist
     */
    private OrderEnum checkPropertyExistsInBean(List<String> propertyNames, Class<?> beanType) {
        String propertyName = utils.kebabCaseToCamelCase(propertyNames.get(0));
        Optional<PropertyDescriptor> foundProperty =
                Arrays.stream(BeanUtils.getPropertyDescriptors(beanType))
                        .filter(descriptor -> descriptor.getName().equals(propertyName))
                        .findFirst();
        if (foundProperty.isPresent()) {
            if (foundProperty.get().getReadMethod() == null) {
                return OrderEnum.EXISTS_PATH_END;
            }
            Type propertyType = foundProperty.get().getReadMethod().getGenericReturnType();
            return checkPropertyExists(propertyNames.subList(1, propertyNames.size()), propertyType);
        } else {
            return OrderEnum.EXISTS_NOT;
        }
    }

    /**
     * Checks if a given type is a terminal type or an enum
     *
     * @param type
     * @return True: the given type is a terminal or an enum False: the given type is neither a terminal type nor an enum
     */
    public boolean isTerminalOrEnum(Type type) {
        if (type instanceof ParameterizedType) {
            return false;
        }
        return TERMINAL_TYPES.contains(type) || ((Class<?>) type).isEnum();
    }


    /**
     * This method takes an array of strings and returns each entry as ArrayList containing the parts of each element.
     * <p>
     * 'inspectit.hello-i-am-testing' would be returned as {'inspectit', 'helloIAmTesting'}
     *
     * @param propertyName A String containing the property path
     * @return a List containing containing the parts of the property path as String
     */
    //@VisibleForTesting
    public List<String> parse(String propertyName) {
        ArrayList<String> result = new ArrayList<>();
        String remainder = propertyName;
        while (remainder != null && !remainder.isEmpty()) {
            remainder = extractExpression(remainder, result);
        }
        return result;
    }

    /**
     * Extracts the first path expression from the given propertyName and appends it to the given result list.
     * The remainder of the property name is returned
     * <p>
     * E.g. inspectit.test.rest -> "inspectit" is added to the list, "test.rest" is returned.
     * E.g. [inspectit.literal].test.rest -> "inspectit.literal" is added to the list, "test.rest" is returned.
     * E.g. [inspectit.literal][test].rest -> "inspectit.literal" is added to the list, "[test].rest" is returned.
     *
     * @param propertyName A String with the path of a property
     * @param result       Reference to the list in which the extracted expressions should be saved in
     * @return the remaining expression
     */
    private String extractExpression(String propertyName, List<String> result) {
        if (propertyName.startsWith("[")) {
            int end = propertyName.indexOf(']');
            if (end == -1) {
                throw new IllegalArgumentException("invalid property path");
            }
            result.add(propertyName.substring(1, end));
            return removeLeadingDot(propertyName.substring(end + 1));
        } else {
            int end = findFirstIndexOf(propertyName, '.', '[');
            if (end == -1) {
                result.add(propertyName);
                return "";
            } else {
                result.add(propertyName.substring(0, end));
                return removeLeadingDot(propertyName.substring(end));
            }
        }
    }

    private int findFirstIndexOf(String propertyName, char first, char second) {
        int firstIndex = propertyName.indexOf(first);
        int secondIndex = propertyName.indexOf(second);
        if (firstIndex == -1) {
            return secondIndex;
        } else if (secondIndex == -1) {
            return firstIndex;
        } else {
            return Math.min(firstIndex, secondIndex);
        }
    }

    private String removeLeadingDot(String string) {
        if (string.startsWith(".")) {
            return string.substring(1);
        } else {
            return string;
        }
    }
}
