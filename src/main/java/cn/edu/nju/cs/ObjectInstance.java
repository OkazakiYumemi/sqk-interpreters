package cn.edu.nju.cs;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runtime representation of a class object instance.
 * Fields are stored in a map; their initial zero values are set during allocation.
 */
final class ObjectInstance {
    final String className; // the real (runtime) class name
    final Map<String, Value> fields = new LinkedHashMap<>();

    ObjectInstance(String className) {
        this.className = className;
    }
}
