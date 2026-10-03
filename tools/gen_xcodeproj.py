"""Generate ios/ShengQiao.xcodeproj from the files in ios/ShengQiao (no Xcode or XcodeGen needed).

    python3 tools/gen_xcodeproj.py [--bundle-id com.example.shengqiao]

Swift and C files go to "Compile Sources", Assets.xcassets to "Copy Bundle Resources";
headers and Info.plist are only referenced. Object IDs are stable (hash of the path).
"""
import argparse
import hashlib
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
IOS = os.path.join(ROOT, 'ios')
APP = 'ShengQiao'
SRC_DIR = os.path.join(IOS, APP)

FILE_TYPES = {
    '.swift': 'sourcecode.swift',
    '.c': 'sourcecode.c.c',
    '.h': 'sourcecode.c.h',
    '.plist': 'text.plist.xml',
    '.xcassets': 'folder.assetcatalog',
}


def oid(*parts):
    return hashlib.md5('/'.join(parts).encode()).hexdigest()[:24].upper()


def q(s):
    """Quote a pbxproj string when needed."""
    import re
    if re.fullmatch(r'[A-Za-z0-9_$/.]+', s) and not s.startswith('$('):
        return s
    return '"' + s.replace('\\', '\\\\').replace('"', '\\"') + '"'


def settings_block(d, indent):
    pad = '\t' * indent
    out = []
    for k in sorted(d):
        v = d[k]
        if isinstance(v, list):
            items = ''.join('%s\t%s,\n' % (pad, q(x)) for x in v)
            out.append('%s%s = (\n%s%s);\n' % (pad, k, items, pad))
        else:
            out.append('%s%s = %s;\n' % (pad, k, q(str(v))))
    return ''.join(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--bundle-id', default='com.shengqiao.app.x7k2')
    ap.add_argument('--team', default='')
    a = ap.parse_args()

    groups = {}          # rel dir -> list of (name, kind)
    files = []           # (relpath, name, type)
    for dirpath, dirnames, filenames in os.walk(SRC_DIR):
        rel = os.path.relpath(dirpath, SRC_DIR)
        rel = '' if rel == '.' else rel
        if rel.endswith('.xcassets') or '.xcassets' in rel:
            dirnames[:] = []
            continue
        dirnames.sort()
        entries = []
        for d in dirnames:
            if d.endswith('.xcassets'):
                files.append((os.path.join(rel, d), d, FILE_TYPES['.xcassets']))
                entries.append(('file', os.path.join(rel, d)))
            else:
                entries.append(('group', os.path.join(rel, d)))
        dirnames[:] = [d for d in dirnames if not d.endswith('.xcassets')]
        for f in sorted(filenames):
            ext = os.path.splitext(f)[1]
            if ext not in FILE_TYPES or f.startswith('.'):
                continue
            files.append((os.path.join(rel, f), f, FILE_TYPES[ext]))
            entries.append(('file', os.path.join(rel, f)))
        groups[rel] = entries

    ids = {
        'project': oid('project'), 'main': oid('group', 'main'), 'products': oid('group', 'products'),
        'target': oid('target', APP), 'appref': oid('product', APP),
        'src': oid('phase', 'sources'), 'res': oid('phase', 'resources'), 'fw': oid('phase', 'frameworks'),
        'pcl': oid('cfglist', 'project'), 'tcl': oid('cfglist', 'target'),
        'pdbg': oid('cfg', 'project', 'Debug'), 'prel': oid('cfg', 'project', 'Release'),
        'tdbg': oid('cfg', 'target', 'Debug'), 'trel': oid('cfg', 'target', 'Release'),
    }
    fref = {p: oid('file', p) for p, _, _ in files}
    gref = {g: oid('group', 'dir', g) for g in groups}
    compile_files = [p for p, n, t in files if t in ('sourcecode.swift', 'sourcecode.c.c')]
    resource_files = [p for p, n, t in files if t == 'folder.assetcatalog']
    bfile = {p: oid('buildfile', p) for p in compile_files + resource_files}
    name_of = {p: n for p, n, _ in files}

    L = []
    w = L.append
    w('// !$*UTF8*$!\n{\n\tarchiveVersion = 1;\n\tclasses = {\n\t};\n\tobjectVersion = 56;\n\tobjects = {\n\n')
    w('/* Begin PBXBuildFile section */\n')
    for p in compile_files:
        w('\t\t%s /* %s in Sources */ = {isa = PBXBuildFile; fileRef = %s /* %s */; };\n' % (bfile[p], name_of[p], fref[p], name_of[p]))
    for p in resource_files:
        w('\t\t%s /* %s in Resources */ = {isa = PBXBuildFile; fileRef = %s /* %s */; };\n' % (bfile[p], name_of[p], fref[p], name_of[p]))
    w('/* End PBXBuildFile section */\n\n')

    w('/* Begin PBXFileReference section */\n')
    w('\t\t%s /* %s.app */ = {isa = PBXFileReference; explicitFileType = wrapper.application; includeInIndex = 0; path = %s.app; sourceTree = BUILT_PRODUCTS_DIR; };\n' % (ids['appref'], APP, APP))
    for p, n, t in files:
        w('\t\t%s /* %s */ = {isa = PBXFileReference; lastKnownFileType = %s; path = %s; sourceTree = "<group>"; };\n' % (fref[p], n, t, q(n)))
    w('/* End PBXFileReference section */\n\n')

    w('/* Begin PBXFrameworksBuildPhase section */\n')
    w('\t\t%s /* Frameworks */ = {\n\t\t\tisa = PBXFrameworksBuildPhase;\n\t\t\tbuildActionMask = 2147483647;\n\t\t\tfiles = (\n\t\t\t);\n\t\t\trunOnlyForDeploymentPostprocessing = 0;\n\t\t};\n' % ids['fw'])
    w('/* End PBXFrameworksBuildPhase section */\n\n')

    w('/* Begin PBXGroup section */\n')
    w('\t\t%s = {\n\t\t\tisa = PBXGroup;\n\t\t\tchildren = (\n\t\t\t\t%s /* %s */,\n\t\t\t\t%s /* Products */,\n\t\t\t);\n\t\t\tsourceTree = "<group>";\n\t\t};\n'
      % (ids['main'], gref[''], APP, ids['products']))
    w('\t\t%s /* Products */ = {\n\t\t\tisa = PBXGroup;\n\t\t\tchildren = (\n\t\t\t\t%s /* %s.app */,\n\t\t\t);\n\t\t\tname = Products;\n\t\t\tsourceTree = "<group>";\n\t\t};\n'
      % (ids['products'], ids['appref'], APP))
    for g, entries in sorted(groups.items()):
        name = APP if g == '' else os.path.basename(g)
        kids = ''
        for kind, p in entries:
            if kind == 'group':
                kids += '\t\t\t\t%s /* %s */,\n' % (gref[p], os.path.basename(p))
            else:
                kids += '\t\t\t\t%s /* %s */,\n' % (fref[p], name_of[p])
        w('\t\t%s /* %s */ = {\n\t\t\tisa = PBXGroup;\n\t\t\tchildren = (\n%s\t\t\t);\n\t\t\tpath = %s;\n\t\t\tsourceTree = "<group>";\n\t\t};\n'
          % (gref[g], name, kids, q(name)))
    w('/* End PBXGroup section */\n\n')

    w('/* Begin PBXNativeTarget section */\n')
    w('\t\t%s /* %s */ = {\n\t\t\tisa = PBXNativeTarget;\n\t\t\tbuildConfigurationList = %s /* Build configuration list for PBXNativeTarget "%s" */;\n'
      '\t\t\tbuildPhases = (\n\t\t\t\t%s /* Sources */,\n\t\t\t\t%s /* Frameworks */,\n\t\t\t\t%s /* Resources */,\n\t\t\t);\n'
      '\t\t\tbuildRules = (\n\t\t\t);\n\t\t\tdependencies = (\n\t\t\t);\n\t\t\tname = %s;\n\t\t\tproductName = %s;\n'
      '\t\t\tproductReference = %s /* %s.app */;\n\t\t\tproductType = "com.apple.product-type.application";\n\t\t};\n'
      % (ids['target'], APP, ids['tcl'], APP, ids['src'], ids['fw'], ids['res'], APP, APP, ids['appref'], APP))
    w('/* End PBXNativeTarget section */\n\n')

    w('/* Begin PBXProject section */\n')
    w('\t\t%s /* Project object */ = {\n\t\t\tisa = PBXProject;\n\t\t\tattributes = {\n\t\t\t\tBuildIndependentTargetsInParallel = 1;\n'
      '\t\t\t\tLastSwiftUpdateCheck = 1600;\n\t\t\t\tLastUpgradeCheck = 1600;\n\t\t\t\tTargetAttributes = {\n\t\t\t\t\t%s = {\n'
      '\t\t\t\t\t\tCreatedOnToolsVersion = 16.0;\n\t\t\t\t\t};\n\t\t\t\t};\n\t\t\t};\n'
      '\t\t\tbuildConfigurationList = %s /* Build configuration list for PBXProject "%s" */;\n'
      '\t\t\tcompatibilityVersion = "Xcode 14.0";\n\t\t\tdevelopmentRegion = "zh-Hans";\n\t\t\thasScannedForEncodings = 0;\n'
      '\t\t\tknownRegions = (\n\t\t\t\ten,\n\t\t\t\tBase,\n\t\t\t\t"zh-Hans",\n\t\t\t);\n\t\t\tmainGroup = %s;\n'
      '\t\t\tproductRefGroup = %s /* Products */;\n\t\t\tprojectDirPath = "";\n\t\t\tprojectRoot = "";\n'
      '\t\t\ttargets = (\n\t\t\t\t%s /* %s */,\n\t\t\t);\n\t\t};\n'
      % (ids['project'], ids['target'], ids['pcl'], APP, ids['main'], ids['products'], ids['target'], APP))
    w('/* End PBXProject section */\n\n')

    w('/* Begin PBXResourcesBuildPhase section */\n')
    res = ''.join('\t\t\t\t%s /* %s in Resources */,\n' % (bfile[p], name_of[p]) for p in resource_files)
    w('\t\t%s /* Resources */ = {\n\t\t\tisa = PBXResourcesBuildPhase;\n\t\t\tbuildActionMask = 2147483647;\n\t\t\tfiles = (\n%s\t\t\t);\n\t\t\trunOnlyForDeploymentPostprocessing = 0;\n\t\t};\n' % (ids['res'], res))
    w('/* End PBXResourcesBuildPhase section */\n\n')

    w('/* Begin PBXSourcesBuildPhase section */\n')
    src = ''.join('\t\t\t\t%s /* %s in Sources */,\n' % (bfile[p], name_of[p]) for p in compile_files)
    w('\t\t%s /* Sources */ = {\n\t\t\tisa = PBXSourcesBuildPhase;\n\t\t\tbuildActionMask = 2147483647;\n\t\t\tfiles = (\n%s\t\t\t);\n\t\t\trunOnlyForDeploymentPostprocessing = 0;\n\t\t};\n' % (ids['src'], src))
    w('/* End PBXSourcesBuildPhase section */\n\n')

    common = {
        'ALWAYS_SEARCH_USER_PATHS': 'NO', 'CLANG_ANALYZER_NONNULL': 'YES', 'CLANG_CXX_LANGUAGE_STANDARD': 'gnu++20',
        'CLANG_ENABLE_MODULES': 'YES', 'CLANG_ENABLE_OBJC_ARC': 'YES', 'CLANG_WARN_BOOL_CONVERSION': 'YES',
        'CLANG_WARN_INT_CONVERSION': 'YES', 'CLANG_WARN_UNREACHABLE_CODE': 'YES', 'COPY_PHASE_STRIP': 'NO',
        'ENABLE_STRICT_OBJC_MSGSEND': 'YES', 'ENABLE_USER_SCRIPT_SANDBOXING': 'YES',
        'GCC_C_LANGUAGE_STANDARD': 'gnu17', 'GCC_NO_COMMON_BLOCKS': 'YES',
        'GCC_WARN_UNUSED_FUNCTION': 'YES', 'GCC_WARN_UNUSED_VARIABLE': 'YES',
        'IPHONEOS_DEPLOYMENT_TARGET': '16.0', 'SDKROOT': 'iphoneos',
    }
    pdbg = dict(common, DEBUG_INFORMATION_FORMAT='dwarf', ENABLE_TESTABILITY='YES', GCC_DYNAMIC_NO_PIC='NO',
                GCC_OPTIMIZATION_LEVEL='0', GCC_PREPROCESSOR_DEFINITIONS=['DEBUG=1', '$(inherited)'],
                ONLY_ACTIVE_ARCH='YES', SWIFT_ACTIVE_COMPILATION_CONDITIONS='DEBUG $(inherited)',
                SWIFT_OPTIMIZATION_LEVEL='-Onone')
    prel = dict(common, DEBUG_INFORMATION_FORMAT='dwarf-with-dsym', ENABLE_NS_ASSERTIONS='NO',
                SWIFT_COMPILATION_MODE='wholemodule', SWIFT_OPTIMIZATION_LEVEL='-O', VALIDATE_PRODUCT='YES')
    target = {
        'ASSETCATALOG_COMPILER_APPICON_NAME': 'AppIcon',
        'ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME': 'AccentColor',
        'CODE_SIGN_STYLE': 'Automatic', 'CURRENT_PROJECT_VERSION': '1', 'DEVELOPMENT_TEAM': a.team,
        'ENABLE_PREVIEWS': 'YES', 'GENERATE_INFOPLIST_FILE': 'NO', 'INFOPLIST_FILE': '%s/Info.plist' % APP,
        'LD_RUNPATH_SEARCH_PATHS': ['$(inherited)', '@executable_path/Frameworks'],
        'MARKETING_VERSION': '2.0', 'PRODUCT_BUNDLE_IDENTIFIER': a.bundle_id, 'PRODUCT_NAME': '$(TARGET_NAME)',
        'SUPPORTED_PLATFORMS': 'iphoneos iphonesimulator', 'SUPPORTS_MACCATALYST': 'NO',
        'SUPPORTS_MAC_DESIGNED_FOR_IPHONE_IPAD': 'NO',
        'SWIFT_EMIT_LOC_STRINGS': 'NO', 'SWIFT_OBJC_BRIDGING_HEADER': '%s/Audio/ShengQiao-Bridging-Header.h' % APP,
        'SWIFT_VERSION': '5.0', 'TARGETED_DEVICE_FAMILY': '1',
        # C 音频核心在 Debug 下也开优化：实时渲染更省电
        'GCC_OPTIMIZATION_LEVEL': 's',
    }
    w('/* Begin XCBuildConfiguration section */\n')
    for key, name, d in (('pdbg', 'Debug', pdbg), ('prel', 'Release', prel), ('tdbg', 'Debug', target), ('trel', 'Release', target)):
        w('\t\t%s /* %s */ = {\n\t\t\tisa = XCBuildConfiguration;\n\t\t\tbuildSettings = {\n%s\t\t\t};\n\t\t\tname = %s;\n\t\t};\n'
          % (ids[key], name, settings_block(d, 4), name))
    w('/* End XCBuildConfiguration section */\n\n')

    w('/* Begin XCConfigurationList section */\n')
    w('\t\t%s /* Build configuration list for PBXProject "%s" */ = {\n\t\t\tisa = XCConfigurationList;\n\t\t\tbuildConfigurations = (\n'
      '\t\t\t\t%s /* Debug */,\n\t\t\t\t%s /* Release */,\n\t\t\t);\n\t\t\tdefaultConfigurationIsVisible = 0;\n\t\t\tdefaultConfigurationName = Release;\n\t\t};\n'
      % (ids['pcl'], APP, ids['pdbg'], ids['prel']))
    w('\t\t%s /* Build configuration list for PBXNativeTarget "%s" */ = {\n\t\t\tisa = XCConfigurationList;\n\t\t\tbuildConfigurations = (\n'
      '\t\t\t\t%s /* Debug */,\n\t\t\t\t%s /* Release */,\n\t\t\t);\n\t\t\tdefaultConfigurationIsVisible = 0;\n\t\t\tdefaultConfigurationName = Release;\n\t\t};\n'
      % (ids['tcl'], APP, ids['tdbg'], ids['trel']))
    w('/* End XCConfigurationList section */\n')
    w('\t};\n\trootObject = %s /* Project object */;\n}\n' % ids['project'])

    proj = os.path.join(IOS, APP + '.xcodeproj')
    os.makedirs(os.path.join(proj, 'xcshareddata', 'xcschemes'), exist_ok=True)
    with open(os.path.join(proj, 'project.pbxproj'), 'w', encoding='utf-8') as f:
        f.write(''.join(L))
    ref = ('<BuildableReference BuildableIdentifier = "primary" BlueprintIdentifier = "%s" BuildableName = "%s.app" '
           'BlueprintName = "%s" ReferencedContainer = "container:%s.xcodeproj"></BuildableReference>') % (ids['target'], APP, APP, APP)
    scheme = '''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion = "1600" version = "1.7">
   <BuildAction parallelizeBuildables = "YES" buildImplicitDependencies = "YES">
      <BuildActionEntries>
         <BuildActionEntry buildForTesting = "YES" buildForRunning = "YES" buildForProfiling = "YES" buildForArchiving = "YES" buildForAnalyzing = "YES">
            %(ref)s
         </BuildActionEntry>
      </BuildActionEntries>
   </BuildAction>
   <TestAction buildConfiguration = "Debug" selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv = "YES">
   </TestAction>
   <LaunchAction buildConfiguration = "Debug" selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB" launchStyle = "0" useCustomWorkingDirectory = "NO" ignoresPersistentStateOnLaunch = "NO" debugDocumentVersioning = "YES" debugServiceExtension = "internal" allowLocationSimulation = "YES">
      <BuildableProductRunnable runnableDebuggingMode = "0">
         %(ref)s
      </BuildableProductRunnable>
   </LaunchAction>
   <ProfileAction buildConfiguration = "Release" shouldUseLaunchSchemeArgsEnv = "YES" savedToolIdentifier = "" useCustomWorkingDirectory = "NO" debugDocumentVersioning = "YES">
      <BuildableProductRunnable runnableDebuggingMode = "0">
         %(ref)s
      </BuildableProductRunnable>
   </ProfileAction>
   <AnalyzeAction buildConfiguration = "Debug">
   </AnalyzeAction>
   <ArchiveAction buildConfiguration = "Release" revealArchiveInOrganizer = "YES">
   </ArchiveAction>
</Scheme>
''' % {'ref': ref}
    with open(os.path.join(proj, 'xcshareddata', 'xcschemes', APP + '.xcscheme'), 'w', encoding='utf-8') as f:
        f.write(scheme)
    print('wrote', proj, '(%d compiled, %d resources, %d files)' % (len(compile_files), len(resource_files), len(files)))


if __name__ == '__main__':
    main()
