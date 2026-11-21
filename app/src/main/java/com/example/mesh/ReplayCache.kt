package com.example.mesh

// LOCCHAT_PATCH_MARKER: replay-cache-fix
// Use a real LinkedHashSet so packets can be marked and bounded safely.
// The cache should be maintained in a separate patch for BleMeshEngine.kt.
