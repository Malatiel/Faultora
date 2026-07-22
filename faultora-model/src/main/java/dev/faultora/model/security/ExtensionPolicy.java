package dev.faultora.model.security;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Set;

/**
 * Policy governing extension capabilities and isolation.
 *
 * @param allowedExtensions        extension identity digests or names (empty = built-ins only)
 * @param requireProcessIsolation  whether extensions must run in separate processes
 * @param maxResourceMemoryMb      memory limit per extension
 * @param maxNetworkDestinations   network destinations the extension may contact
 * @param secretCapabilities       secret handle IDs the extension may access
 */
public record ExtensionPolicy(
        Set<String> allowedExtensions,
        boolean requireProcessIsolation,
        int maxResourceMemoryMb,
        Set<String> maxNetworkDestinations,
        Set<String> secretCapabilities
) {}
