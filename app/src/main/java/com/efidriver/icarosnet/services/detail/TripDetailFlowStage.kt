package com.efidriver.icarosnet.services.detail

enum class TripDetailFlowStage {
    IDLE,
    CARD_CLICKED,
    MODAL_RENDERED,
    DETAIL_CONTENT_CHANGED,
    MAP_CHANGED,
    OCR_REQUESTED,
    OCR_COMPLETED
}
