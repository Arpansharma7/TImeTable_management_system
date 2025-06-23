$(document).ready(function() {
  $('#faculty').select2({
    placeholder: 'Select faculty members',
    allowClear: true
  });
  
  $('#sections').select2({
    placeholder: 'Select sections',
    allowClear: true
  });

  // Preferred days select2 for faculty add/edit
  $('#preferredDays').select2({
    placeholder: 'Select preferred days',
    allowClear: true
  });

  // Initial fetch of reference data
  fetchReferenceData();

  // Navigation for Help and Get Started buttons
  $('.nav-btn:contains("Help")').on('click', function() {
    const heroSection = document.getElementById('helpSection');
    if (heroSection) heroSection.scrollIntoView({ behavior: 'smooth' });
  });
  $('.nav-btn:contains("Get Started")').on('click', function() {
    const inputSection = document.getElementById('inputSection');
    if (inputSection) inputSection.scrollIntoView({ behavior: 'smooth' });
  });

  // Hide timetable container if empty
  function checkTimetableVisibility() {
    const timetable = $('#timetable');
    if (!timetable.html().trim()) {
      timetable.hide();
    } else {
      timetable.show();
    }
  }
  checkTimetableVisibility();
  // Also check after timetable is generated
  $(document).on('timetableUpdated', checkTimetableVisibility);
});

// Global variables
let subjectQueue = [];
let referenceData = {
  faculty: [],
  sections: [],
  rooms: [],
  timeSlots: []
};

// Backend API URL
const API_BASE_URL = 'http://localhost:8080';

// UI Message Handlers
const appMessagesDiv = $('#appMessages');

function showAppMessage(message, type = 'info', isDismissible = false) {
  appMessagesDiv.empty();
  const alertClass = `alert alert-${type}`;
  let messageHtml = `<div class="${alertClass}" role="alert">${message}`;;
  if (isDismissible) {
    messageHtml += `<button type="button" class="close" data-dismiss="alert" aria-label="Close"><span aria-hidden="true">&times;</span></button>`;
  }
  messageHtml += `</div>`;
  appMessagesDiv.append(messageHtml);
}

function hideAppMessage() {
  appMessagesDiv.empty();
}

function showLoading(message = 'Loading...') {
  showAppMessage(`<div class="spinner-border text-primary" role="status"><span class="sr-only">Loading...</span></div> ${message}`, 'info');
}

function hideLoading() {
  hideAppMessage();
}

function showError(message) {
  showAppMessage(`<strong>Error:</strong> ${message} <button onclick="window.location.reload()" class="btn btn-sm btn-danger ml-3">Refresh Page</button>`, 'danger', true);
}

function showSuccess(message) {
  showAppMessage(`<strong>Success:</strong> ${message}`, 'success', true);
}

// Fetch reference data from backend
async function fetchReferenceData() {
  showLoading('Loading reference data...');
  try {
    const response = await fetch(`${API_BASE_URL}/api/reference-data`); // Updated endpoint
    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(`Failed to fetch reference data: ${response.status} - ${errorText || response.statusText}`);
    }
    referenceData = await response.json();
    
    // Populate faculty dropdown
    const facultySelect = $('#faculty');
    facultySelect.empty(); // Clear existing options
    referenceData.faculty.forEach(faculty => {
      facultySelect.append(new Option(faculty.name, faculty.id));
    });
    
    // Populate sections dropdown
    const sectionsSelect = $('#sections');
    sectionsSelect.empty(); // Clear existing options
    referenceData.sections.forEach(section => {
      sectionsSelect.append(new Option(section.name, section.id));
    });
    hideLoading();
  } catch (error) {
    console.error('Error fetching reference data:', error);
    showError('Failed to load reference data. Please refresh the page.');
  }
}

// Handle section scope change
$('#sectionScope').on('change', function() {
  const sectionsGroup = $('#sectionsGroup');
  if (this.value === 'SPECIFIC' || this.value === 'EXCLUDE') {
    sectionsGroup.show();
  } else {
    sectionsGroup.hide();
    $('#sections').val(null).trigger('change');
  }
});

// Handle form submission
$('#subjectForm').on('submit', function(e) {
  e.preventDefault();
  
  const subjectName = $('#subjectName').val().trim();
  const selectedFaculties = [].concat($('#faculty').val() || []);
  const faculty = selectedFaculties.length > 0 ? selectedFaculties[0] : null; // Take only the first faculty ID

  const slotDuration = parseInt($('#duration').val()); // Duration (in slots)
  const lecturesPerWeek = parseInt($('#lecturesPerWeek').val()); // Lectures per week - assuming this ID exists
  const sectionScope = $('#sectionScope').val();
  const sectionsSelected = [].concat($('#sections').val() || []); // sections selected from dropdown
  
  // Calculate total duration for backend (slots per lecture * lectures per week)
  const totalDuration = slotDuration * lecturesPerWeek;

  if (!subjectName || !faculty || isNaN(slotDuration) || isNaN(lecturesPerWeek) || faculty === null) {
    showAppMessage('Please fill in all required fields and select at least one faculty.', 'warning', true);
    return;
  }
  
  if ((sectionScope === 'SPECIFIC' || sectionScope === 'EXCLUDE') && sectionsSelected.length === 0) {
    showAppMessage('Please select at least one section', 'warning', true);
    return;
  }
  
  // Determine the final sections array to be stored in subjectQueue based on scope
  let sectionsForQueue = [];
  if (sectionScope === 'ALL') {
    sectionsForQueue = referenceData.sections.map(s => s.id);
  } else if (sectionScope === 'EXCLUDE') {
    sectionsForQueue = referenceData.sections
      .filter(s => !sectionsSelected.includes(String(s.id)))
      .map(s => s.id);
  } else { // SPECIFIC
    sectionsForQueue = sectionsSelected;
  }

  // Create a unique key for the subject based on subject name and resolved sections
  // Sort section IDs to ensure consistent key generation regardless of input order
  const subjectKey = `${subjectName}-${[...sectionsForQueue].sort().join(',')}`;

  let existingSubjectIndex = -1;
  for (let i = 0; i < subjectQueue.length; i++) {
    const currentSubject = subjectQueue[i];
    // The `sections` property in `currentSubject` should now already be resolved based on its own scope
    const currentSubjectKey = `${currentSubject.name}-${[...currentSubject.sections].sort().join(',')}`;

    if (subjectKey === currentSubjectKey) {
      existingSubjectIndex = i;
      break;
    }
  }

  const newSubject = {
    id: Date.now(), // Use new ID for new entry, or keep existing ID if updating
    name: subjectName,
    faculty: selectedFaculties, // Store ALL selected faculty IDs
    slotDuration: slotDuration, // Store original slot duration for display
    lecturesPerWeek: lecturesPerWeek, // Store lectures per week for display
    duration: totalDuration, // Total duration for backend processing
    sectionScope: sectionScope,
    sections: sectionsForQueue // Store the resolved sections array
  };

  if (existingSubjectIndex !== -1) {
    // Update existing subject
    newSubject.id = subjectQueue[existingSubjectIndex].id; // Keep original ID
    subjectQueue[existingSubjectIndex] = newSubject;
    showAppMessage('Subject updated in queue.', 'info', true);
  } else {
    // Add new subject
    subjectQueue.push(newSubject);
    showAppMessage('Subject added to queue.', 'success', true);
  }

  updateSubjectQueue();
  this.reset();
  $('#faculty').val(null).trigger('change');
  $('#sections').val(null).trigger('change');
  $('#sectionsGroup').hide();
});

// Update subject queue display
function updateSubjectQueue() {
  const queueDiv = $('#subjectQueue');
  queueDiv.empty();

  subjectQueue.forEach((subject, index) => {
    // Show all selected faculty names as chips
    const facultyNames = (subject.faculty && subject.faculty.length > 0)
      ? subject.faculty.map(fid => referenceData.faculty.find(f => f.id == fid)?.name).filter(Boolean)
      : [];

    // Use the `sections` property directly as it's now pre-resolved
    let sectionNames = [];
    if (subject.sections && subject.sections.length > 0) {
      sectionNames = subject.sections.map(id => {
        const sec = referenceData.sections.find(s => s.id == id);
        return sec ? sec.name : null;
      }).filter(Boolean);
    }

    const facultyChips = facultyNames.length
      ? facultyNames.map(name => `<span class='chip'>${name}</span>`).join(' ')
      : '<span class="chip chip-empty">None</span>';
    const sectionChips = sectionNames.length
      ? sectionNames.map(name => `<span class='chip chip-section'>${name}</span>`).join(' ')
      : '<span class="chip chip-empty">None</span>';

    const queueItem = $(
      `<div class="queue-item-card" style="margin-bottom: 16px; padding: 16px;">
        <div class="queue-item-header" style="font-size: 1.15em; font-weight: 600; margin-bottom: 0.5em;"><strong>${subject.name}</strong></div>
        <div class="queue-item-row" style="margin-bottom: 0.3em;"><span class="queue-label">Faculty:</span> ${facultyChips}</div>
        <div class="queue-item-row" style="margin-bottom: 0.3em;"><span class="queue-label">Duration:</span> <span class="chip chip-duration">${subject.slotDuration} slot(s)</span></div>
        <div class="queue-item-row" style="margin-bottom: 0.3em;"><span class="queue-label">Lectures per week:</span> <span class="chip chip-lectures">${subject.lecturesPerWeek}</span></div>
        <div class="queue-item-row" style="margin-bottom: 0.3em;"><span class="queue-label">Sections:</span> ${sectionChips}</div>
        <button class="queue-remove-btn" onclick="removeFromQueue(${index})">Remove</button>
      </div>`
    );
    queueDiv.append(queueItem);
  });
}

// Remove subject from queue
function removeFromQueue(index) {
  subjectQueue.splice(index, 1);
  updateSubjectQueue();
}

// Generate timetable
$('#generateTimetable').on('click', async function() {
  if (subjectQueue.length === 0) {
    showAppMessage('Please add at least one subject to the queue', 'warning', true);
    return;
  }
  
  showLoading('Generating timetable...');
  // Transform subjectQueue into backend-expected format
  const expandedSubjects = [];
  subjectQueue.forEach(subject => {
    // The `subject.sections` array is now already resolved based on its scope
    const sectionsToProcessForBackend = subject.sections; 

    // Create a separate entry for each section associated with this subject
    if (sectionsToProcessForBackend.length > 0) {
        sectionsToProcessForBackend.forEach(sectionId => {
            // Create 'frequency' number of entries for each section
            for (let i = 0; i < subject.lecturesPerWeek; i++) {
                expandedSubjects.push({
                    subjectName: subject.name,
                    facultyIds: subject.faculty,
                    duration: subject.slotDuration, // Send original slot duration, not totalDuration
                    frequency: subject.lecturesPerWeek, // Send original lectures per week
                    sectionId: sectionId
                });
            }
        });
    } else {
        // Handle cases where a subject might somehow have no sections after resolution (e.g., error in selection/data)
        // This case should ideally not happen if section selection is enforced, but good for robustness.
        expandedSubjects.push({
            subjectName: subject.name,
            facultyIds: subject.faculty,
            duration: subject.slotDuration,
            frequency: subject.lecturesPerWeek,
            sectionId: null // Indicate no specific section, backend should handle as a skipped slot
        });
    }
  });

  try {
    const response = await fetch(`${API_BASE_URL}/api/generate-timetable`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(expandedSubjects),
    });

    const result = await response.json();

    if (response.ok) {
      showSuccess('Timetable generated successfully!');
      displayTimetable(result.timetable);

      // Display skipped slots if any
      const skippedSlotsDiv = $('#skippedSlotsMessages');
      skippedSlotsDiv.empty();
      if (result.skippedSlots && result.skippedSlots.length > 0) {
        let skippedHtml = '<div class="alert alert-warning mt-3" role="alert"><strong>Skipped Slots:</strong><ul>';
        result.skippedSlots.forEach(slot => {
          // let message = `
          // (rest of the function is missing, so comment this out for now)
        });
        skippedHtml += '</ul></div>';
        skippedSlotsDiv.append(skippedHtml);
      }
    }
  } catch (error) {
    console.error('Error generating timetable:', error);
    showError('Failed to generate timetable. Please try again later.');
  }
});

// Minimal displayTimetable implementation to render timetable in the UI
function displayTimetable(timetable) {
  const timetableDiv = $('#timetable');
  timetableDiv.empty();
  if (!Array.isArray(timetable) || timetable.length === 0) {
    timetableDiv.append('<div class="alert alert-info">No timetable generated.</div>');
    return;
  }

  // Get all unique individual sections from timetable (not groups)
  const sectionSet = new Set();
  timetable.forEach(entry => {
    if (Array.isArray(entry.sections)) {
      entry.sections.forEach(s => sectionSet.add(s.name));
    } else if (entry.section?.name) {
      sectionSet.add(entry.section.name);
    }
  });
  const sectionList = Array.from(sectionSet).sort();

  // Render a single searchable dropdown for section selection
  let dropdownHtml = `<div style="margin-bottom:1em"><label for='sectionSelect'><b>Select section to view timetable:</b></label> <select id='sectionSelect' style='min-width:200px; margin-left:8px;'><option value=''>-- Select Section --</option>`;
  sectionList.forEach(sec => {
    dropdownHtml += `<option value="${sec}">${sec}</option>`;
  });
  dropdownHtml += `</select></div>`;
  timetableDiv.append(dropdownHtml);

  // Use Select2 for searchable dropdown if available
  if ($.fn.select2) {
    $('#sectionSelect').select2({ placeholder: 'Select section', allowClear: true });
  }

  // Table rendering function for a selected section
  function renderSectionTable(sectionName) {
    $('#timetableTable').remove();
    if (!sectionName) return;
    // Find all entries where this section is present (either as individual or in a group)
    const filtered = timetable.filter(entry => {
      if (Array.isArray(entry.sections)) {
        return entry.sections.some(s => s.name === sectionName);
      } else if (entry.section?.name) {
        return entry.section.name === sectionName;
      }
      return false;
    });
    if (filtered.length === 0) {
      timetableDiv.append(`<div id='timetableTable'><div class='alert alert-warning'>No classes found for section <b>${sectionName}</b>.</div></div>`);
      return;
    }
    // Sort by day (Monday-Saturday), then start time
    const dayOrder = ['Monday','Tuesday','Wednesday','Thursday','Friday','Saturday','Sunday'];
    filtered.sort((a, b) => {
      const dayA = a.timeslot?.day || '';
      const dayB = b.timeslot?.day || '';
      const d = dayOrder.indexOf(dayA) - dayOrder.indexOf(dayB);
      if (d !== 0) return d;
      return (a.timeslot?.start_time || a.timeslot?.startTime || '').localeCompare(b.timeslot?.start_time || b.timeslot?.startTime || '');
    });
    let html = `<div id='timetableTable'><table class=\"timetable-table\">
      <thead>
        <tr>
          <th>Subject</th>
          <th>Faculty</th>
          <th>Sections</th>
          <th>Room</th>
          <th>Day</th>
          <th>Start Time</th>
          <th>End Time</th>
        </tr>
      </thead>
      <tbody>`;
    filtered.forEach(entry => {
      let sectionNames = '';
      if (Array.isArray(entry.sections)) {
        sectionNames = entry.sections.map(s => s.name).join(', ');
      } else if (entry.section?.name) {
        sectionNames = entry.section.name;
      }
      html += `<tr>
        <td>${entry.subjectName || ''}</td>
        <td>${entry.faculty?.name || ''}</td>
        <td>${sectionNames}</td>
        <td>${entry.room?.roomNumber || entry.room?.name || ''}</td>
        <td>${entry.timeslot?.day || ''}</td>
        <td>${entry.timeslot?.start_time || entry.timeslot?.startTime || ''}</td>
        <td>${entry.timeslot?.end_time || entry.timeslot?.endTime || ''}</td>
      </tr>`;
    });
    html += '</tbody></table></div>';
    timetableDiv.append(html);
  }

  // Only show timetable after selecting a section
  $('#sectionSelect').on('change', function() {
    renderSectionTable(this.value);
  });
}

// Make notification close button functional
$(document).on('click', '.close', function() {
  $(this).closest('.alert').remove();
});

