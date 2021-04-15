/*
 * Copyright Red Hat
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package io.narayana.lra.arquillian.deployment;

import org.jboss.shrinkwrap.api.Archive;

/**
 * This interface represents a sort of guide to indicate that extension
 * classes used to generate deployment scenarios MUST implement
 * this interface
 *
 * @author <a href="mailto:jfinelli@redhat.com">Jmanuel Finelli</a>
 */
public interface Deployment<T extends Archive<T>> {

    // No constructor should be declared in the classes that implement
    // this interface. Only the default constructor should be used
    Archive<T> create(String deploymentName);
}