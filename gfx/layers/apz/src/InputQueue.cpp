/* -*- Mode: C++; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 2 -*- */
/* vim: set sw=2 ts=8 et tw=80 : */
/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

#include "InputQueue.h"

#include "AsyncPanZoomController.h"
#include "gfxPrefs.h"
#include "InputBlockState.h"
#include "LayersLogging.h"
#include "OverscrollHandoffState.h"

#define INPQ_LOG(...)
// #define INPQ_LOG(...) printf_stderr("INPQ: " __VA_ARGS__)

namespace mozilla {
namespace layers {

InputQueue::InputQueue()
{
}

InputQueue::~InputQueue() {
  mTouchBlockQueue.Clear();
}

nsEventStatus
InputQueue::ReceiveInputEvent(const nsRefPtr<AsyncPanZoomController>& aTarget,
                              bool aTargetConfirmed,
                              const InputData& aEvent,
                              uint64_t* aOutInputBlockId) {
  AsyncPanZoomController::AssertOnControllerThread();

  if (aEvent.mInputType != MULTITOUCH_INPUT) {
    // The return value for non-touch input is only used by tests, so just pass
    // through the return value for now. This can be changed later if needed.
    // TODO (bug 1098430): we will eventually need to have smarter handling for
    // non-touch events as well.
    return aTarget->HandleInputEvent(aEvent);
  }

  TouchBlockState* block = nullptr;
  if (aEvent.AsMultiTouchInput().mType == MultiTouchInput::MULTITOUCH_START) {
    block = StartNewTouchBlock(aTarget, aTargetConfirmed, false);
    INPQ_LOG("started new touch block %p for target %p\n", block, aTarget.get());

    // We want to cancel animations here as soon as possible (i.e. without waiting for
    // content responses) because a finger has gone down and we don't want to keep moving
    // the content under the finger. However, to prevent "future" touchstart events from
    // interfering with "past" animations (i.e. from a previous touch block that is still
    // being processed) we only do this animation-cancellation if there are no older
    // touch blocks still in the queue.
    if (block == CurrentTouchBlock()) {
      // XXX using the chain from |block| here may be wrong in cases where the
      // target isn't confirmed and the real target turns out to be something
      // else. For now assume this is rare enough that it's not an issue.
      if (block->GetOverscrollHandoffChain()->HasFastMovingApzc()) {
        // If we're already in a fast fling, then we want the touch event to stop the fling
        // and to disallow the touch event from being used as part of a fling.
        block->SetDuringFastMotion();
      }
      block->GetOverscrollHandoffChain()->CancelAnimations();
    }

    bool waitForMainThread = !aTargetConfirmed;
    if (!gfxPrefs::LayoutEventRegionsEnabled()) {
      waitForMainThread |= aTarget->NeedToWaitForContent();
    }
    if (block->IsDuringFastMotion()) {
      waitForMainThread = false;
    }
    if (waitForMainThread) {
      // We either don't know for sure if aTarget is the right APZC, or we may
      // need to wait to give content the opportunity to prevent-default the
      // touch events. Either way we schedule a timeout so the main thread stuff
      // can run.
      ScheduleMainThreadTimeout(aTarget, block->GetBlockId());
    } else {
      // Content won't prevent-default this, so we can just pretend like we scheduled
      // a timeout and it expired. Note that we will still receive a ContentReceivedTouch
      // callback for this block, and so we need to make sure we adjust the touch balance.
      INPQ_LOG("not waiting for content response on block %p\n", block);
      block->TimeoutContentResponse();
    }
  } else if (mTouchBlockQueue.IsEmpty()) {
    NS_WARNING("Received a non-start touch event while no touch blocks active!");
  } else {
    // this touch is part of the most-recently created block
    block = mTouchBlockQueue.LastElement().get();
    INPQ_LOG("received new event in block %p\n", block);
  }

  if (!block) {
    return nsEventStatus_eIgnore;
  }
  if (aOutInputBlockId) {
    *aOutInputBlockId = block->GetBlockId();
  }

  // Note that the |aTarget| the APZCTM sent us may contradict the confirmed
  // target set on the block. In this case the confirmed target (which may be
  // null) should take priority. This is equivalent to just always using the
  // target (confirmed or not) from the block.
  nsRefPtr<AsyncPanZoomController> target = block->GetTargetApzc();

  nsEventStatus result = nsEventStatus_eIgnore;
  // XXX calling ArePointerEventsConsumable on |target| may be wrong here if
  // the target isn't confirmed and the real target turns out to be something
  // else. For now assume this is rare enough that it's not an issue.
  if (block->IsDuringFastMotion()) {
    result = nsEventStatus_eConsumeNoDefault;
  } else if (target && target->ArePointerEventsConsumable(block, aEvent.AsMultiTouchInput().mTouches.Length())) {
    result = nsEventStatus_eConsumeDoDefault;
  }

  if (block == CurrentTouchBlock() && block->IsReadyForHandling()) {
    INPQ_LOG("current touch block is ready with target %p preventdefault %d\n",
        target.get(), block->IsDefaultPrevented());
    if (!target || block->IsDefaultPrevented()) {
      return result;
    }
    target->HandleInputEvent(aEvent);
    return result;
  }

  // Otherwise, add it to the queue for the touch block
  block->AddEvent(aEvent.AsMultiTouchInput());
  return result;
}

uint64_t
InputQueue::InjectNewTouchBlock(AsyncPanZoomController* aTarget)
{
  TouchBlockState* block = StartNewTouchBlock(aTarget,
    /* aTargetConfirmed = */ true,
    /* aCopyAllowedTouchBehaviorFromCurrent = */ true);
  INPQ_LOG("%p injecting new touch block with id %" PRIu64 " and target %p\n",
    this, block->GetBlockId(), aTarget);
  ScheduleMainThreadTimeout(aTarget, block->GetBlockId());
  return block->GetBlockId();
}

TouchBlockState*
InputQueue::StartNewTouchBlock(const nsRefPtr<AsyncPanZoomController>& aTarget,
                               bool aTargetConfirmed,
                               bool aCopyAllowedTouchBehaviorFromCurrent)
{
  TouchBlockState* newBlock = new TouchBlockState(aTarget, aTargetConfirmed);
  if (gfxPrefs::TouchActionEnabled() && aCopyAllowedTouchBehaviorFromCurrent) {
    newBlock->CopyAllowedTouchBehaviorsFrom(*CurrentTouchBlock());
  }

  // We're going to start a new block, so clear out any depleted blocks at the head of the queue.
  // See corresponding comment in ProcessPendingInputBlocks.
  while (!mTouchBlockQueue.IsEmpty()) {
    if (mTouchBlockQueue[0]->IsReadyForHandling() && !mTouchBlockQueue[0]->HasEvents()) {
      INPQ_LOG("discarding depleted touch block %p\n", mTouchBlockQueue[0].get());
      mTouchBlockQueue.RemoveElementAt(0);
    } else {
      break;
    }
  }

  // Add the new block to the queue.
  mTouchBlockQueue.AppendElement(newBlock);
  return newBlock;
}

TouchBlockState*
InputQueue::CurrentTouchBlock() const
{
  AsyncPanZoomController::AssertOnControllerThread();

  MOZ_ASSERT(!mTouchBlockQueue.IsEmpty());
  return mTouchBlockQueue[0].get();
}

bool
InputQueue::HasReadyTouchBlock() const
{
  return !mTouchBlockQueue.IsEmpty() && mTouchBlockQueue[0]->IsReadyForHandling();
}

void
InputQueue::ScheduleMainThreadTimeout(const nsRefPtr<AsyncPanZoomController>& aTarget, uint64_t aInputBlockId) {
  INPQ_LOG("scheduling main thread timeout for target %p\n", aTarget.get());
  aTarget->PostDelayedTask(
    NewRunnableMethod(this, &InputQueue::MainThreadTimeout, aInputBlockId),
    gfxPrefs::APZContentResponseTimeout());
}

void
InputQueue::MainThreadTimeout(const uint64_t& aInputBlockId) {
  AsyncPanZoomController::AssertOnControllerThread();

  INPQ_LOG("got a main thread timeout; block=%" PRIu64 "\n", aInputBlockId);
  bool success = false;
  for (size_t i = 0; i < mTouchBlockQueue.Length(); i++) {
    if (mTouchBlockQueue[i]->GetBlockId() == aInputBlockId) {
      // time out the touch-listener response and also confirm the existing
      // target apzc in the case where the main thread doesn't get back to us
      // fast enough.
      success = mTouchBlockQueue[i]->TimeoutContentResponse();
      success |= mTouchBlockQueue[i]->SetConfirmedTargetApzc(mTouchBlockQueue[i]->GetTargetApzc());
      break;
    }
  }
  if (success) {
    ProcessPendingInputBlocks();
  }
}

void
InputQueue::ContentReceivedTouch(uint64_t aInputBlockId, bool aPreventDefault) {
  AsyncPanZoomController::AssertOnControllerThread();

  INPQ_LOG("got a content response; block=%" PRIu64 "\n", aInputBlockId);
  bool success = false;
  for (size_t i = 0; i < mTouchBlockQueue.Length(); i++) {
    if (mTouchBlockQueue[i]->GetBlockId() == aInputBlockId) {
      success = mTouchBlockQueue[i]->SetContentResponse(aPreventDefault);
      break;
    }
  }
  if (success) {
    ProcessPendingInputBlocks();
  }
}

void
InputQueue::SetConfirmedTargetApzc(uint64_t aInputBlockId, const nsRefPtr<AsyncPanZoomController>& aTargetApzc) {
  AsyncPanZoomController::AssertOnControllerThread();

  INPQ_LOG("got a target apzc; block=%" PRIu64 " guid=%s\n",
    aInputBlockId, aTargetApzc ? Stringify(aTargetApzc->GetGuid()).c_str() : "");
  bool success = false;
  for (size_t i = 0; i < mTouchBlockQueue.Length(); i++) {
    if (mTouchBlockQueue[i]->GetBlockId() == aInputBlockId) {
      success = mTouchBlockQueue[i]->SetConfirmedTargetApzc(aTargetApzc);
      break;
    }
  }
  if (success) {
    ProcessPendingInputBlocks();
  } else {
    NS_WARNING("INPQ received useless SetConfirmedTargetApzc");
  }
}

void
InputQueue::SetAllowedTouchBehavior(uint64_t aInputBlockId, const nsTArray<TouchBehaviorFlags>& aBehaviors) {
  AsyncPanZoomController::AssertOnControllerThread();

  INPQ_LOG("got allowed touch behaviours; block=%" PRIu64 "\n", aInputBlockId);
  bool success = false;
  for (size_t i = 0; i < mTouchBlockQueue.Length(); i++) {
    if (mTouchBlockQueue[i]->GetBlockId() == aInputBlockId) {
      success = mTouchBlockQueue[i]->SetAllowedTouchBehaviors(aBehaviors);
      break;
    }
  }
  if (success) {
    ProcessPendingInputBlocks();
  } else {
    NS_WARNING("INPQ received useless SetAllowedTouchBehavior");
  }
}

void
InputQueue::ProcessPendingInputBlocks() {
  AsyncPanZoomController::AssertOnControllerThread();

  while (true) {
    TouchBlockState* curBlock = CurrentTouchBlock();
    if (!curBlock->IsReadyForHandling()) {
      break;
    }

    INPQ_LOG("processing input block %p; preventDefault %d target %p\n",
        curBlock, curBlock->IsDefaultPrevented(),
        curBlock->GetTargetApzc().get());
    nsRefPtr<AsyncPanZoomController> target = curBlock->GetTargetApzc();
    // target may be null here if the initial target was unconfirmed and then
    // we later got a confirmed null target. in that case drop the events.
    if (!target) {
      curBlock->DropEvents();
    } else if (curBlock->IsDefaultPrevented()) {
      curBlock->DropEvents();
      target->ResetInputState();
    } else {
      while (curBlock->HasEvents()) {
        target->HandleInputEvent(curBlock->RemoveFirstEvent());
      }
    }
    MOZ_ASSERT(!curBlock->HasEvents());

    if (mTouchBlockQueue.Length() == 1) {
      // If |curBlock| is the only touch block in the queue, then it is still
      // active and we cannot remove it yet. We only know that a touch block is
      // over when we start the next one. This block will be removed by the code
      // in StartNewTouchBlock, where new touch blocks are added.
      break;
    }

    // If we get here, we know there are more touch blocks in the queue after
    // |curBlock|, so we can remove |curBlock| and try to process the next one.
    INPQ_LOG("discarding depleted touch block %p\n", curBlock);
    mTouchBlockQueue.RemoveElementAt(0);
  }
}

} // namespace layers
} // namespace mozilla
