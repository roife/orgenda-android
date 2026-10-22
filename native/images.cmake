include(ExternalProject)
set(CODECS "${CMAKE_CURRENT_LIST_DIR}/../vendor")
set(JPEG_BUILD "${CMAKE_CURRENT_BINARY_DIR}/jpeg")
set(TIFF_BUILD "${CMAKE_CURRENT_BINARY_DIR}/tiff")
set(CODEC_ARGS
    -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE} -DANDROID_ABI=${ANDROID_ABI}
    -DANDROID_PLATFORM=${ANDROID_PLATFORM} -DCMAKE_BUILD_TYPE=Release
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON)
ExternalProject_Add(
  jpeg_vendor
  SOURCE_DIR "${CODECS}/libjpeg-turbo"
  BINARY_DIR "${JPEG_BUILD}"
  CMAKE_ARGS ${CODEC_ARGS}
             -DENABLE_SHARED=OFF
             -DENABLE_STATIC=ON
             -DWITH_TURBOJPEG=OFF
             -DWITH_TOOLS=OFF
             -DWITH_TESTS=OFF
             -DWITH_SIMD=OFF
  BUILD_COMMAND ${CMAKE_COMMAND} --build <BINARY_DIR> --target jpeg-static
  INSTALL_COMMAND ""
  BUILD_BYPRODUCTS "${JPEG_BUILD}/libjpeg.a")
ExternalProject_Add(
  tiff_vendor
  SOURCE_DIR "${CODECS}/libtiff"
  BINARY_DIR "${TIFF_BUILD}"
  DEPENDS jpeg_vendor
  CMAKE_ARGS ${CODEC_ARGS}
             -DBUILD_SHARED_LIBS=OFF
             -Dtiff-tools=OFF
             -Dtiff-tests=OFF
             -Dtiff-contrib=OFF
             -Dtiff-docs=OFF
             -Dtiff-install=OFF
             -Dlzma=OFF
             -Dzstd=OFF
             -Dwebp=OFF
             -Dlerc=OFF
             -Djbig=OFF
             -Dlibdeflate=OFF
             -Djpeg=ON
             -Dold-jpeg=ON
             -DJPEG_INCLUDE_DIR=${CODECS}/libjpeg-turbo/src
             -DJPEG_LIBRARY=${JPEG_BUILD}/libjpeg.a
             -DCMAKE_C_FLAGS=-I${JPEG_BUILD}
  BUILD_COMMAND ${CMAKE_COMMAND} --build <BINARY_DIR> --target tiff
  INSTALL_COMMAND ""
  BUILD_BYPRODUCTS "${TIFF_BUILD}/libtiff/libtiff.a")
add_library(orgenda_images SHARED tiff.c)
add_dependencies(orgenda_images tiff_vendor)
target_include_directories(orgenda_images PRIVATE "${CODECS}/libtiff/libtiff"
                                                  "${TIFF_BUILD}/libtiff")
target_link_libraries(orgenda_images PRIVATE "${TIFF_BUILD}/libtiff/libtiff.a"
                                             "${JPEG_BUILD}/libjpeg.a" z m)
