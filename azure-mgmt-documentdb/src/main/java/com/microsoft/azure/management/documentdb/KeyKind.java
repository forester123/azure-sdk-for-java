/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 *
 * Code generated by Microsoft (R) AutoRest Code Generator.
 */

package com.microsoft.azure.management.documentdb;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Defines values for KeyKind.
 */
public final class KeyKind {
    /** Static value primary for KeyKind. */
    public static final KeyKind PRIMARY = new KeyKind("primary");

    /** Static value secondary for KeyKind. */
    public static final KeyKind SECONDARY = new KeyKind("secondary");

    /** Static value primaryReadonly for KeyKind. */
    public static final KeyKind PRIMARY_READONLY = new KeyKind("primaryReadonly");

    /** Static value secondaryReadonly for KeyKind. */
    public static final KeyKind SECONDARY_READONLY = new KeyKind("secondaryReadonly");

    private String value;

    /**
     * Creates a custom value for KeyKind.
     * @param value the custom value
     */
    public KeyKind(String value) {
        this.value = value;
    }

    @JsonValue
    @Override
    public String toString() {
        return value;
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof KeyKind)) {
            return false;
        }
        if (obj == this) {
            return true;
        }
        KeyKind rhs = (KeyKind) obj;
        if (value == null) {
            return rhs.value == null;
        } else {
            return value.equals(rhs.value);
        }
    }
}
