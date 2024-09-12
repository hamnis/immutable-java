package net.hamnaberg.json.pointer;

import net.hamnaberg.json.Json;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class JsonPointer {
    private final List<Ref> path;

    public static JsonPointer compile(String pattern) {
        if (pattern == null || pattern.trim().isEmpty()) {
            return new JsonPointer(List.of());
        }
        return new JsonPointer(new JsonPointerParser().parse(pattern));
    }

    private JsonPointer(List<Ref> path) {
        this.path = path;
    }

    @Override
    public String toString() {
        if (path.isEmpty()) return "";
        return path.stream().map(ref -> ref.fold(
                r -> String.valueOf(r.index),
                r -> escape(r.name),
                () -> "-"
        )).collect(Collectors.joining("/", "/", ""));
    }

    private String escape(String str) {
        return str.replace("~", "~0").replace("/", "~1");
    }

    public Optional<Json.JValue> select(Json.JValue value) {
        if (path.isEmpty()) {
            return Optional.of(value);
        }

        final Iterator<Ref> iterator = path.iterator();
        Json.JValue current = value;
        while (iterator.hasNext()) {
            Ref ref = iterator.next();
            final Json.JValue c = current;
            current = ref.fold(
                    arrayRef -> foldToJson(
                            c,
                            obj -> obj.get(String.valueOf(arrayRef.index)).orElse(obj),
                            arr -> arr.get(arrayRef.index).orElse(arr)
                    ),
                    propertyRef -> foldToJson(
                            c,
                            obj -> obj.get(String.valueOf(propertyRef.name)).orElse(obj),
                            Json.JValue::asJValue
                    ),
                    () -> {
                        throw new IllegalStateException("List index is out-of-bounds");
                    }
            );
            if (!iterator.hasNext() && current != value) {
                return Optional.of(current);
            }
        }

        return Optional.empty();
    }

    public Json.JValue add(Json.JValue json, Json.JValue value) {
        if (path.isEmpty()) {
            return value;
        }

        Iterator<Ref> iterator = path.iterator();
        return addImpl(iterator, iterator.next(), json, value);
    }

    public Json.JValue remove(Json.JValue json) {
        if (path.isEmpty()) {
            return json;
        }

        Iterator<Ref> iterator = path.iterator();
        return updateImpl(iterator, iterator.next(), json, Optional.empty());
    }

    public Json.JValue replace(Json.JValue json, Json.JValue value) {
        if (path.isEmpty()) {
            return value;
        }

        Iterator<Ref> iterator = path.iterator();
        return updateImpl(iterator, iterator.next(), json, Optional.of(value));
    }

    public Json.JValue copy(Json.JValue json, JsonPointer from) {
        //TODO: Optimize? we have 3 traversals of the graph
        Optional<Json.JValue> selected = from.select(json);
        return selected.map(v -> add(json, v)).orElse(json);
    }

    public Json.JValue move(Json.JValue json, JsonPointer from) {
        //TODO: Optimize? we have 3 traversals of the graph
        Optional<Json.JValue> selected = from.select(json);
        return selected.map(v -> add(from.remove(json), v)).orElse(json);
    }

    public boolean test(Json.JValue json, Json.JValue value) {
        return select(json).map(value::equals).orElse(false);
    }

    private Json.JValue foldToJson(Json.JValue value, Function<Json.JObject, Json.JValue> fObject, Function<Json.JArray, Json.JValue> fArray) {
        return value.fold(Json.Folder.from(
                Json.JValue::asJValue,
                Json.JValue::asJValue,
                Json.JValue::asJValue,
                fObject,
                fArray,
                Json::jNull
        ));
    }

    private Json.JValue updateImpl(Iterator<Ref> path, Ref ref, Json.JValue context, Optional<Json.JValue> updateValue) {
        return ref.fold(
                arrayRef -> foldToJson(
                        context,
                        obj -> replaceObject(path, context, updateValue, String.valueOf(arrayRef.index)),
                        arr -> {
                            int index = arrayRef.index;
                            Json.JArray array = context.asJsonArrayOrEmpty();
                            if (!path.hasNext()) {
                                if (updateValue.isPresent()) {
                                    array.get(index).orElseThrow(() -> new IllegalStateException("No value at index: " + index));
                                    return array.replace(index, updateValue.get());
                                } else {
                                    return array.remove(index);
                                }
                            } else {
                                Json.JValue value = array.get(index).orElseThrow(() -> new IllegalStateException("No value at index: " + index));
                                return array.replace(index, updateImpl(path, path.next(), value, updateValue));
                            }
                        }
                ),
                propertyRef -> foldToJson(
                        context,
                        obj -> replaceObject(path, context, updateValue, propertyRef.name),
                        Json.JValue::asJValue
                ),
                () -> {
                    throw new IllegalStateException("List index is out-of-bounds");
                }
        );
    }

    private Json.JValue replaceObject(Iterator<Ref> path, Json.JValue context, Optional<Json.JValue> updateValue, String name) {
        Json.JObject object = context.asJsonObjectOrEmpty();
        if (!path.hasNext()) {
            return updateValue.map(jValue -> object.put(name, jValue)).orElseGet(() -> object.remove(name));
        } else {
            Json.JValue value = object.get(name).orElseThrow(() -> new IllegalStateException("No value with name: " + name));
            return object.put(name, updateImpl(path, path.next(), value, updateValue));
        }
    }

    private Json.JValue addImpl(Iterator<Ref> path, Ref ref, Json.JValue context, Json.JValue valueToInsert) {
        return ref.fold(
                arrayRef -> foldToJson(
                        context,
                        obj -> {
                            String name = String.valueOf(arrayRef.index);
                            return addObject(path, obj, valueToInsert, name);
                        },
                        arr -> {
                            int index = arrayRef.index;
                            if (!path.hasNext()) {
                                if (arr.size() >= index) {
                                    return arr.insert(index, valueToInsert);
                                } else {
                                    throw new IllegalStateException(String.format("List index %s is out-of-bounds", index));
                                }
                            } else {
                                Json.JValue value = arr.get(index).orElseThrow(() -> new IllegalStateException(String.format("List index %s is out-of-bounds", index)));
                                return arr.replace(index, addImpl(path, path.next(), value, valueToInsert));
                            }
                        }
                ),
                propertyRef -> foldToJson(
                        context,
                        obj -> addObject(path, obj, valueToInsert, propertyRef.name),
                        Json.JValue::asJValue
                ),
                () ->
                        foldToJson(
                                context,
                                j -> j,
                                arr -> {
                                    if (path.hasNext()) {
                                        throw new IllegalStateException("Nonsense to have more values after a end-of-array");
                                    }
                                    return arr.append(valueToInsert);
                                }
                        )
        );
    }

    private Json.JValue addObject(Iterator<Ref> path, Json.JValue context, Json.JValue valueToInsert, String name) {
        Json.JObject object = context.asJsonObjectOrEmpty();
        if (!path.hasNext()) {
            return object.put(name, valueToInsert);
        } else {
            Json.JValue value = object.get(name).orElseThrow(() -> new IllegalStateException("No value with name: " + name));
            return object.put(name, addImpl(path, path.next(), value, valueToInsert));
        }
    }
}
