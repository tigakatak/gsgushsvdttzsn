package com.redt.distro

object DistroRegistry {
    val allDistros: List<Distro> = listOf(
        Distro(
            name = "almalinux",
            displayName = "AlmaLinux 9",
            description = "Stable RHEL-compatible, bug-for-bug with CentOS. 64-bit ARM only.",
            baseUrl = "https://easycli.sh/proot-distro/almalinux-{arch}-pd-v4.37.0.tar.xz",
            sha256 = "3e58affaf2b8c7c4999bb1f17bd773fe9447c6b7f8f2213caae82289b371a224",
            packageManager = "dnf"
        ),
        Distro(
            name = "alpine",
            displayName = "Alpine Linux 3.24.1",
            description = "Minimal (~5MB), fast, security-focused. Recommended for low disk space.",
            baseUrl = "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.1-aarch64.tar.gz",
            sha256 = "f55a90f69052c5bd6f92cb09a8f47065970830b194c917a006fb94028e721259",
            packageManager = "apk"
        ),
        Distro(
            name = "arch",
            displayName = "Arch Linux",
            description = "Rolling release, latest packages. 64-bit ARM only.",
            baseUrl = "https://easycli.sh/proot-distro/archlinux-{arch}-pd-v4.37.0.tar.xz",
            sha256 = "718151cc4adad701223c689a7e4690cb7710b7b16e9b23617b671856ff04d563",
            packageManager = "pacman"
        ),
        Distro(
            name = "artix",
            displayName = "Artix Linux",
            description = "Arch without systemd (OpenRC/Runit). Rolling release.",
            baseUrl = "https://easycli.sh/proot-distro/artix-{arch}-pd-v4.37.0.tar.xz",
            sha256 = "fe499e00903db5342969ea2d87a97349c78b43e4cb53f0388cec5ad8cc35e92c",
            packageManager = "pacman"
        ),
        Distro(
            name = "debian",
            displayName = "Debian 12-LTS",
            description = "Stable, well-supported, large package repository.",
            baseUrl = "https://github.com/termux/proot-distro/releases/download/v4.7.0/debian-bookworm-{arch}-pd-v4.7.0.tar.xz",
            sha256 = "4baa32280cc70b67e2c650777c1d974349f0cdf23afaabc305ad3bc6182b8df8",
            packageManager = "apt"
        ),
        Distro(
            name = "fedora",
            displayName = "Fedora 43",
            description = "Modern, innovative, upstream for RHEL. 64-bit ARM only.",
            baseUrl = "https://easycli.sh/proot-distro/fedora-{arch}-pd-v4.37.0.tar.xz",
            sha256 = "eb86202ef9887dc315e93c627bef3b6a825da871129ab3de91466ab2c2e06019",
            packageManager = "dnf"
        ),
        Distro(
            name = "manjaro",
            displayName = "Manjaro",
            description = "User-friendly Arch-based rolling release. 64-bit ARM only.",
            baseUrl = "https://easycli.sh/proot-distro/manjaro-{arch}-pd-v4.37.0.tar.xz",
            sha256 = "90fd86130d440b6d6ed6408b21306189eb41fe07d0026aab836ae203a1c419a4",
            packageManager = "pacman"
        ),
        Distro(
            name = "rocky",
            displayName = "Rocky Linux 10",
            description = "RHEL-compatible enterprise distro. 64-bit ARM only.",
            baseUrl = "https://easycli.sh/proot-distro/rocky-{arch}-pd-v4.37.0.tar.xz",
            sha256 = "0282a82a75e0b17aa0f72622847ee0bfda85fa84bb6cf49bc72c5515816c47f0",
            packageManager = "dnf"
        ),
        Distro(
            name = "ubuntu",
            displayName = "Ubuntu 24.04",
            description = "User-friendly, great community, latest packages.",
            baseUrl = "https://github.com/termux/proot-distro/releases/download/v4.11.0/ubuntu-noble-{arch}-pd-v4.11.0.tar.xz",
            sha256 = "a8883244a7031559a2bd8dc16b7d8afc947930b611819d8a28a09545097a6ba5",
            packageManager = "apt"
        ),
    )
}
