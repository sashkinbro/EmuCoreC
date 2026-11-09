# Keep the imported ARM64 GHC spill fix reproducible on fresh submodule checkouts.
function(emucorec_apply_arm64_ghc_patch llvm_root patch_file)
    set(frame_lowering "${llvm_root}/llvm/lib/Target/AArch64/AArch64FrameLowering.cpp")
    file(READ "${frame_lowering}" frame_lowering_source)
    if(frame_lowering_source MATCHES "GHCMFI[.]CreateSpillStackObject")
        return()
    endif()

    find_program(EMUCOREC_PATCH_GIT NAMES git REQUIRED)
    execute_process(
        COMMAND "${EMUCOREC_PATCH_GIT}" apply --check --whitespace=nowarn "${patch_file}"
        WORKING_DIRECTORY "${llvm_root}"
        RESULT_VARIABLE patch_check
        ERROR_VARIABLE patch_error
    )
    if(NOT patch_check EQUAL 0)
        message(FATAL_ERROR "Cannot apply the ARM64 GHC emergency spill fix to this LLVM revision: ${patch_error}")
    endif()
    execute_process(
        COMMAND "${EMUCOREC_PATCH_GIT}" apply --whitespace=nowarn "${patch_file}"
        WORKING_DIRECTORY "${llvm_root}"
        RESULT_VARIABLE patch_result
        ERROR_VARIABLE patch_error
    )
    if(NOT patch_result EQUAL 0)
        message(FATAL_ERROR "Failed to apply the ARM64 GHC emergency spill fix: ${patch_error}")
    endif()
endfunction()
